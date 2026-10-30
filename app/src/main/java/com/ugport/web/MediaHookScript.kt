package com.ugport.web

import org.json.JSONObject

/**
 * 注入到页面的媒体探测脚本。只上报状态，不暴露任何控制入口；
 * 控制命令只能由原生通过 __ugMediaCommand 下发。
 */
object MediaHookScript {

    fun inject(): String = SCRIPT

    fun probe(): String = "window.__ugMediaProbe && window.__ugMediaProbe();"

    fun command(json: String): String =
        "window.__ugMediaCommand && window.__ugMediaCommand(" + JSONObject.quote(json) + ");"

    /**
     * 页面重建后续播：脚本会等到音频元素出现再 seek + play。
     * 标题对不上时不 seek 也不自动播放，交给用户决定。
     */
    fun resume(positionMs: Long, title: String, ttlMs: Long = 120_000L): String {
        val json = JSONObject()
            .put("cmd", "resume")
            .put("pos", positionMs.coerceAtLeast(0L))
            .put("title", title.take(120))
            .put("ttl", ttlMs)
            .toString()
        return command(json)
    }

    private val SCRIPT = """
        (function () {
          if (window.__ugMediaVersion >= 5) return;
          window.__ugMediaVersion = 5;

          var EVENTS = ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'durationchange',
                        'timeupdate', 'volumechange', 'waiting', 'stalled', 'error', 'emptied',
                        'loadstart', 'seeked'];
          var MAX_ELEMENTS = 16;
          var tracked = [];
          var lastJson = '';
          var lastSentAt = 0;
          var sendTimer = 0;
          var scanTimer = 0;
          var lastTimeUpdateAt = 0;
          var handlers = {};
          var msHooked = false;
          var lastSkipSig = '';
          var lastSkipCheckAt = 0;
          var lastActiveEl = null;
          var SKIP_MAX_DEPTH = 4;
          var SKIP_ICON_SELECTOR =
            'svg[class*="lucide-skip-"], svg[data-lucide="skip-back"], svg[data-lucide="skip-forward"]';

          function text(value, max) {
            if (value === null || value === undefined) return '';
            var s = String(value).replace(/[\u0000-\u001f\u007f]/g, ' ');
            s = s.trim();
            return s.length > max ? s.slice(0, max) : s;
          }

          function finite(value, fallback) {
            var n = Number(value);
            return isFinite(n) ? n : fallback;
          }

          function bridge() {
            try { return window.UgMedia || null; } catch (e) { return null; }
          }

          function mediaSession() {
            try { return navigator.mediaSession || null; } catch (e) { return null; }
          }

          function capability(action) {
            return typeof handlers[action] === 'function';
          }

          function remember(action, handler) {
            if (action === null || action === undefined) return;
            var name = String(action);
            if (name !== 'previoustrack' && name !== 'nexttrack') return;
            if (typeof handler === 'function') handlers[name] = handler;
            else delete handlers[name];
            schedule(120);
          }

          function hookMediaSession() {
            if (msHooked) return;
            var ms = mediaSession();
            if (!ms) return;
            var proto = null;
            try { proto = window.MediaSession ? window.MediaSession.prototype : null; } catch (e) {}
            if (proto && typeof proto.setActionHandler === 'function') {
              if (proto.__ugMediaSAHook === 2) { msHooked = true; return; }
              var original = proto.setActionHandler;
              proto.setActionHandler = function (action, handler) {
                try {
                  return original.apply(this, arguments);
                } finally {
                  try { remember(action, handler); } catch (e) {}
                }
              };
              try { proto.__ugMediaSAHook = 2; } catch (e) {}
              msHooked = true;
              return;
            }
            if (typeof ms.setActionHandler !== 'function') return;
            if (ms.__ugMediaSAHook === 2) { msHooked = true; return; }
            var instOriginal = ms.setActionHandler;
            ms.setActionHandler = function (action, handler) {
              try {
                return instOriginal.apply(this, arguments);
              } finally {
                try { remember(action, handler); } catch (e) {}
              }
            };
            try { ms.__ugMediaSAHook = 2; } catch (e) {}
            msHooked = true;
          }

          function capabilities(el) {
            var has = !!mediaSession();
            var msPrev = has && capability('previoustrack');
            var msNext = has && capability('nexttrack');
            var domPrev = false;
            var domNext = false;
            if ((!msPrev || !msNext) && el) {
              if (!msPrev) domPrev = !!skipButton(el, 'prev');
              if (!msNext) domNext = !!skipButton(el, 'next');
            }
            return { msPrev: msPrev, msNext: msNext, domPrev: domPrev, domNext: domNext };
          }

          function skipIconDir(svg) {
            var parts = (svg.getAttribute('class') || '').split(/\s+/);
            for (var i = 0; i < parts.length; i++) {
              if (parts[i] === 'lucide-skip-back' || parts[i] === 'lucide-skip-back-icon') return 'prev';
              if (parts[i] === 'lucide-skip-forward' || parts[i] === 'lucide-skip-forward-icon') return 'next';
            }
            var data = svg.getAttribute('data-lucide');
            if (data === 'skip-back') return 'prev';
            if (data === 'skip-forward') return 'next';
            return '';
          }

          function buttonUsable(btn) {
            if (!btn || !btn.isConnected) return false;
            if (btn.disabled === true) return false;
            if (btn.getAttribute('disabled') !== null) return false;
            if (btn.getAttribute('aria-disabled') === 'true') return false;
            return true;
          }

          function buttonVisible(btn) {
            try { return btn.getClientRects().length > 0; } catch (e) { return false; }
          }

          // hiddenOut 用于记住祖先链里第一个不可见按钮；可见按钮优先返回
          function iconButton(root, dir, hiddenOut) {
            var icons;
            try { icons = root.querySelectorAll(SKIP_ICON_SELECTOR); } catch (e) { return null; }
            for (var i = 0; i < icons.length; i++) {
              if (skipIconDir(icons[i]) !== dir) continue;
              var btn = icons[i].closest ? icons[i].closest('button, [role="button"]') : null;
              if (!buttonUsable(btn)) continue;
              if (buttonVisible(btn)) return btn;
              if (hiddenOut && !hiddenOut.value) hiddenOut.value = btn;
            }
            return null;
          }

          function iconPresent(root, dir) {
            var icons;
            try { icons = root.querySelectorAll(SKIP_ICON_SELECTOR); } catch (e) { return false; }
            for (var i = 0; i < icons.length; i++) {
              if (skipIconDir(icons[i]) === dir) return true;
            }
            return false;
          }

          function skipButton(el, dir) {
            if (!el || !el.isConnected) return null;
            var scope = el.parentElement || document;
            // 部分听书页面：播放按钮 aria-label="player.play"，其父级就是 skip 控制组
            var play;
            try { play = scope.querySelector('button[aria-label^="player."]'); } catch (e) { play = null; }
            if (play && play.parentElement) {
              var group = play.parentElement;
              var groupHidden = { value: null };
              var direct = iconButton(group, dir, groupHidden);
              if (direct) return direct;
              if (groupHidden.value) return groupHidden.value;
              // 控制组里存在该按钮但被禁用（首章/末章），不要再去祖先链里找别的播放器
              if (iconPresent(group, dir)) return null;
            }
            var node = el.parentElement;
            var depth = 0;
            var hidden = { value: null };
            while (node && depth <= SKIP_MAX_DEPTH &&
                   node !== document.body && node !== document.documentElement) {
              var found = iconButton(node, dir, hidden);
              if (found) return found;
              node = node.parentElement;
              depth++;
            }
            return hidden.value;
          }

          function metadata() {
            var title = '';
            var artist = '';
            var album = '';
            try {
              var md = navigator.mediaSession && navigator.mediaSession.metadata;
              if (md) {
                title = text(md.title, 160);
                artist = text(md.artist, 160);
                album = text(md.album, 160);
              }
            } catch (e) {}
            if (!title) title = text(document.title, 160);
            return { title: title, artist: artist, album: album };
          }

          function score(el) {
            var value = 0;
            if (!el.paused && !el.ended) value += 100;
            if (!el.muted && el.volume > 0) value += 20;
            if (el.readyState >= 2) value += 10;
            value += Math.min(9, finite(el.currentTime, 0) / 60);
            return value;
          }

          function pick() {
            var best = null;
            var bestScore = -1;
            for (var i = 0; i < tracked.length; i++) {
              var el = tracked[i];
              if (!el) continue;
              if (!el.isConnected && (el.paused || el.ended)) continue;
              var value = score(el);
              if (value > bestScore) {
                best = el;
                bestScore = value;
              }
            }
            return best || lastActiveEl;
          }

          function snapshot() {
            var el = pick();
            var meta = metadata();
            var caps = capabilities(el);
            if (!el) {
              var msState = 'none';
              try {
                var ms = navigator.mediaSession;
                if (ms && ms.playbackState) msState = String(ms.playbackState);
              } catch (e) {}
              var playing = msState === 'playing';
              return {
                v: 5, has: false, playing: playing,
                state: playing ? 'playing' : (msState === 'paused' ? 'paused' : 'none'),
                pos: 0, dur: 0, rate: 1, kind: 'audio',
                canPrev: caps.msPrev, canNext: caps.msNext,
                title: meta.title, artist: meta.artist, album: meta.album
              };
            }
            var active = !el.paused && !el.ended;
            var state = 'paused';
            if (el.error) state = 'error';
            else if (el.ended) state = 'ended';
            else if (active) state = el.readyState < 3 ? 'buffering' : 'playing';
            var rawDuration = el.duration;
            var duration = finite(rawDuration, 0);
            if (duration < 0) duration = 0;
            // 直播流 duration=Infinity，没有可切曲目，隐藏上一首/下一首
            var live = rawDuration === Infinity;
            return {
              v: 5, has: true, playing: active, state: state,
              pos: Math.max(0, Math.round(finite(el.currentTime, 0) * 1000)),
              dur: Math.round(duration * 1000),
              rate: finite(el.playbackRate, 1),
              kind: el.tagName === 'VIDEO' ? 'video' : 'audio',
              canPrev: (caps.msPrev && !live) || caps.domPrev,
              canNext: (caps.msNext && !live) || caps.domNext,
              title: meta.title, artist: meta.artist, album: meta.album
            };
          }

          function send(force) {
            var json;
            try { json = JSON.stringify(snapshot()); } catch (e) { return; }
            var now = Date.now();
            if (!force && json === lastJson && now - lastSentAt < 10000) return;
            if (force && json === lastJson && now - lastSentAt < 250) return;
            var api = bridge();
            if (!api || !api.postState) return;
            lastJson = json;
            lastSentAt = now;
            try { api.postState(json); } catch (e) {}
          }

          function schedule(delay) {
            if (sendTimer) clearTimeout(sendTimer);
            sendTimer = setTimeout(function () {
              sendTimer = 0;
              send(true);
            }, delay === undefined ? 120 : delay);
          }

          function onEvent(event) {
            if (!event) return;
            if (event.target && (event.type === 'play' || event.type === 'playing')) {
              lastActiveEl = event.target;
            }
            if (event.type === 'timeupdate') {
              var now = Date.now();
              if (now - lastTimeUpdateAt < 1000) return;
              lastTimeUpdateAt = now;
              send(false);
              return;
            }
            schedule(120);
          }

          function hook(el) {
            if (!el || el.__ugMediaHooked) return;
            el.__ugMediaHooked = true;
            tracked.push(el);
            for (var i = 0; i < EVENTS.length; i++) {
              try { el.addEventListener(EVENTS[i], onEvent, true); } catch (e) {}
            }
            if (tracked.length > MAX_ELEMENTS) {
              var kept = [];
              for (var j = tracked.length - 1; j >= 0 && kept.length < MAX_ELEMENTS; j--) {
                var item = tracked[j];
                if (item && (item.isConnected || !item.paused)) kept.push(item);
              }
              tracked = kept.reverse();
            }
          }

          function prune() {
            var kept = [];
            for (var i = 0; i < tracked.length; i++) {
              var el = tracked[i];
              if (el && (el.isConnected || !el.paused)) kept.push(el);
            }
            tracked = kept;
          }

          function scan() {
            var nodes;
            hookMediaSession();
            prune();
            try { nodes = document.querySelectorAll('audio,video'); } catch (e) { return; }
            var changed = false;
            for (var i = 0; i < nodes.length && tracked.length < MAX_ELEMENTS; i++) {
              if (!nodes[i].__ugMediaHooked) {
                hook(nodes[i]);
                changed = true;
              }
            }
            if (changed) schedule(80);
            var skipNow = Date.now();
            if (skipNow - lastSkipCheckAt >= 1000 &&
                (typeof handlers['previoustrack'] !== 'function' ||
                 typeof handlers['nexttrack'] !== 'function')) {
              lastSkipCheckAt = skipNow;
              var picked = pick();
              var sig = picked
                ? (skipButton(picked, 'prev') ? '1' : '0') + (skipButton(picked, 'next') ? '1' : '0')
                : '-';
              if (sig !== lastSkipSig) {
                lastSkipSig = sig;
                schedule(120);
              }
            }
            tryResume();
          }

          /** 只取页面自己写入 MediaSession 的标题，document.title 常用于站点名，不能用来判断章节。 */
          function mediaTitle() {
            try {
              var md = navigator.mediaSession && navigator.mediaSession.metadata;
              if (md) return text(md.title, 160);
            } catch (e) {}
            return '';
          }

          function resumeTitleMatches() {
            var job = window.__ugResume;
            if (!job) return false;
            var want = job.title || '';
            var cur = mediaTitle();
            if (!want || !cur) return true;
            return cur === want || cur.indexOf(want) >= 0 || want.indexOf(cur) >= 0;
          }

          /** 页面自己调用 play 时先把位置补上，避免从本章开头出声。 */
          function preResumeSeek(el) {
            var job = window.__ugResume;
            if (!job || !el) return;
            if (!resumeTitleMatches()) return;
            var pos = finite(job.pos, 0);
            if (pos <= 0) return;
            try {
              if (isFinite(el.duration) && el.duration > 0) {
                pos = Math.min(pos, Math.max(0, el.duration - 2000));
              }
              el.currentTime = pos / 1000;
            } catch (e) {}
          }

          function tryResume() {
            var job = window.__ugResume;
            if (!job) return;
            if (Date.now() > job.until) {
              window.__ugResume = null;
              return;
            }
            var el = pick() || lastActiveEl;
            if (!el) return;
            if (!resumeTitleMatches()) {
              // 章节对不上：不 seek 也不自动播放
              window.__ugResume = null;
              send(true);
              return;
            }
            var pos = finite(job.pos, 0);
            var apply = function () {
              window.__ugResume = null;
              if (pos > 0) {
                try {
                  if (isFinite(el.duration) && el.duration > 0) {
                    pos = Math.min(pos, Math.max(0, el.duration - 2000));
                  }
                  el.currentTime = pos / 1000;
                } catch (e) {}
              }
              safePlay(el);
            };
            if (pos <= 0 || el.readyState >= 1) {
              apply();
            } else {
              try {
                el.addEventListener('loadedmetadata', function onMeta() {
                  el.removeEventListener('loadedmetadata', onMeta);
                  apply();
                });
              } catch (e) {
                apply();
              }
            }
          }

          function allMedia() {
            var list = [];
            for (var i = 0; i < tracked.length; i++) {
              if (tracked[i]) list.push(tracked[i]);
            }
            return list;
          }

          function safePlay(el) {
            if (!el) return;
            try {
              var promise = el.play();
              if (promise && promise.catch) promise.catch(function () {});
            } catch (e) {}
          }

          window.__ugMediaProbe = function () {
            try {
              hookMediaSession();
              scan();
              send(true);
              return JSON.stringify(snapshot());
            } catch (e) {
              return '';
            }
          };

          window.__ugMediaCommand = function (payload) {
            var command = payload;
            if (typeof payload === 'string') {
              try { command = JSON.parse(payload); } catch (e) { return 'bad'; }
            }
            if (!command || !command.cmd) return 'bad';
            var cmd = String(command.cmd);
            var el = pick();
            if (!el) {
              var list = allMedia();
              el = list.length ? list[0] : null;
            }
            try {
              if (cmd === 'play') {
                safePlay(el);
              } else if (cmd === 'pause' || cmd === 'stop') {
                var all = allMedia();
                for (var i = 0; i < all.length; i++) {
                  try { all[i].pause(); } catch (e) {}
                }
              } else if (cmd === 'toggle') {
                if (el) {
                  if (el.paused) safePlay(el);
                  else el.pause();
                }
              } else if (cmd === 'seek') {
                var target = Number(command.pos);
                if (el && isFinite(target)) {
                  var seconds = target / 1000;
                  if (isFinite(el.duration) && el.duration > 0) {
                    seconds = Math.min(seconds, el.duration);
                  }
                  el.currentTime = Math.max(0, seconds);
                }
              } else if (cmd === 'next' || cmd === 'prev') {
                var action = cmd === 'next' ? 'nexttrack' : 'previoustrack';
                var handled = false;
                var skipHandler = handlers[action];
                if (typeof skipHandler === 'function') {
                  try {
                    var skipResult = skipHandler.call(mediaSession() || undefined, { action: action });
                    if (skipResult && typeof skipResult.catch === 'function') {
                      skipResult.catch(function () {});
                    }
                    handled = true;
                  } catch (e) {
                    handled = false;
                  }
                }
                if (!handled) {
                  var fallback = skipButton(el, cmd === 'next' ? 'next' : 'prev');
                  if (!fallback) {
                    send(true);
                    return 'unsupported';
                  }
                  try {
                    fallback.click();
                  } catch (e) {
                    return 'error';
                  }
                }
              } else if (cmd === 'resume') {
                window.__ugResume = {
                  pos: Math.max(0, finite(command.pos, 0)),
                  title: text(command.title, 120),
                  until: Date.now() + Math.max(1000, finite(command.ttl, 120000))
                };
                tryResume();
              } else if (cmd === 'recover') {
                var target = pick() || lastActiveEl;
                if (!target) return 'no-media';
                var src = target.currentSrc || target.src || '';
                if (target.__ugRecoverSrc === src &&
                    target.__ugRecoverAt &&
                    Date.now() - target.__ugRecoverAt < 60000) {
                  return 'throttled';
                }
                target.__ugRecoverSrc = src;
                target.__ugRecoverAt = Date.now();
                var pos = finite(target.currentTime, 0);
                var isBlob = src.indexOf('blob:') === 0;
                var isLive = target.duration === Infinity;
                if (isBlob || isLive) {
                  safePlay(target);
                } else if (target.readyState < 3 || target.error || target.networkState === 3) {
                  try {
                    target.addEventListener('loadedmetadata', function onMeta() {
                      target.removeEventListener('loadedmetadata', onMeta);
                      try { target.currentTime = pos; } catch (e) {}
                      safePlay(target);
                    });
                    target.load();
                  } catch (e) {
                    return 'error';
                  }
                } else {
                  safePlay(target);
                }
              } else {
                return 'unknown';
              }
            } catch (e) {
              return 'error';
            }
            schedule(80);
            return 'ok';
          };

          try {
            var originalPlay = HTMLMediaElement.prototype.play;
            HTMLMediaElement.prototype.play = function () {
              try { hook(this); } catch (e) {}
              try { preResumeSeek(this); } catch (e) {}
              return originalPlay.apply(this, arguments);
            };
          } catch (e) {}

          hookMediaSession();

          try {
            if (document.readyState === 'loading') {
              document.addEventListener('DOMContentLoaded', function () {
                scan();
                send(true);
              });
            } else {
              scan();
            }
          } catch (e) {}

          try {
            var observer = new MutationObserver(function () {
              if (scanTimer) return;
              scanTimer = setTimeout(function () {
                scanTimer = 0;
                scan();
              }, 200);
            });
            observer.observe(document, {
              childList: true,
              subtree: true,
              attributes: true,
              attributeFilter: ['disabled', 'aria-disabled']
            });
          } catch (e) {}

          window.addEventListener('load', function () {
            scan();
            send(true);
          });

          document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'visible') {
              scan();
              send(true);
            }
          });

          document.addEventListener('play', onEvent, true);
          document.addEventListener('pause', onEvent, true);
          document.addEventListener('ended', onEvent, true);

          window.addEventListener('pagehide', function () {
            var api = bridge();
            if (!api || !api.postState) return;
            try {
              api.postState('{"v":5,"has":false,"playing":false,"state":"none","pos":0,"dur":0,"canPrev":false,"canNext":false}');
            } catch (e) {}
          });
        })();
    """.trimIndent()
}
