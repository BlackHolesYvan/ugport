package com.ugport.web

/**
 * 注入到端口页的 JS 片段。
 *
 * 所有脚本都是幂等的，可以重复执行。返回字符串的脚本会把结果通过
 * `evaluateJavascript` 回传给 Kotlin，调用方按各自 KDoc 的契约解析。
 */

internal const val MEDIA_PAUSE_SCRIPT =
    "(function(){try{var m=document.querySelectorAll('audio,video');" +
        "for(var i=0;i<m.length;i++){try{m[i].pause();}catch(e){}}}catch(e){}})();"

internal val VH_FIX_SCRIPT = """
    (function(){
      try {
        var h = window.innerHeight;
        if (!document.body || !h) return 0;
        // 只有 vh 真的算成 0 时才处理
        var probe = document.createElement('div');
        probe.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;';
        document.documentElement.appendChild(probe);
        probe.style.height = '100vh';
        var unit = probe.getBoundingClientRect().height;
        document.documentElement.removeChild(probe);
        if (unit > 0) return 0;
        function convert(v){
          return v.replace(/([0-9.]+)(dvh|svh|lvh|vh)/g, function(m, n){
            return (parseFloat(n) * h / 100) + 'px';
          });
        }
        var count = 0;
        function walk(rules){
          for (var i = 0; i < rules.length; i++) {
            var r = rules[i];
            if (r.style) {
              var props = ['height', 'minHeight', 'maxHeight'];
              for (var p = 0; p < props.length; p++) {
                var v = r.style[props[p]];
                if (v && /(dvh|svh|lvh|vh)/.test(v)) {
                  try { r.style[props[p]] = convert(v); count++; } catch (e) {}
                }
              }
            }
            if (r.cssRules && r.cssRules.length) walk(r.cssRules);
          }
        }
        for (var i = 0; i < document.styleSheets.length; i++) {
          try { var rules = document.styleSheets[i].cssRules; if (rules) walk(rules); } catch (e) {}
        }
        var inline = document.querySelectorAll('[style*="vh"]');
        for (var j = 0; j < inline.length; j++) {
          var props2 = ['height', 'minHeight', 'maxHeight'];
          for (var k = 0; k < props2.length; k++) {
            var iv = inline[j].style[props2[k]];
            if (iv && /(dvh|svh|lvh|vh)/.test(iv)) {
              try { inline[j].style[props2[k]] = convert(iv); count++; } catch (e) {}
            }
          }
        }
        if (count > 0) { try { window.dispatchEvent(new Event('resize')); } catch (e) {} }
        return count;
      } catch (e) { return 0; }
    })();
""".trimIndent()

internal val STALL_SCRIPT = """
    (function(){
      try {
        var b = document.body;
        if (!b) return 'ok';
        return (b.innerText || '').trim().length === 0 ? 'stall' : 'ok';
      } catch (e) { return 'ok'; }
    })();
""".trimIndent()

internal val SHORT_PAGE_SCRIPT = """
    (function(){
      try {
        var vh = window.innerHeight, b = document.body;
        if (!b || vh <= 0) return 'skip';
        return b.getBoundingClientRect().height < vh * 0.5 ? 'short' : 'ok';
      } catch (e) { return 'ok'; }
    })();
""".trimIndent()

/**
 * 空白页判定：body 高度为 0，或既没有文字也没有可见的图片/画布/控件。
 * 只用于“清缓存重载一次”的兜底，不做像素截图判断。
 */
internal val BLANK_CHECK_SCRIPT = """
    (function(){
      try {
        var b = document.body;
        if (!b) return 'blank';
        // 有文字就说明页面并非空白（body 高度 0 属于布局问题，由 PAGE_INFO_SCRIPT 修复）
        if ((b.innerText || '').trim().length > 0) return 'ok';
        var nodes = document.querySelectorAll('img,canvas,video,svg,iframe,input,button,select,textarea');
        for (var i = 0; i < nodes.length; i++) {
          var style = getComputedStyle(nodes[i]);
          var rect = nodes[i].getBoundingClientRect();
          if (style.display !== 'none' && style.visibility !== 'hidden' &&
              rect.width > 2 && rect.height > 2) return 'ok';
        }
        return 'blank';
      } catch (e) { return 'ok'; }
    })();
""".trimIndent()

internal val PAGE_INFO_SCRIPT = """
    (function(){
      try {
        var vw = window.innerWidth, vh = window.innerHeight;
        var repaired = [];
        function rectOf(el){
          if (!el) return null;
          var b = el.getBoundingClientRect();
          return [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)];
        }
        // 部分 WebUI（qBittorrent、PanSou）在 WebView 里 body 高度会算成 0：
        // 可能是 body 塌陷，也可能是 100dvh 解析为 0 后被 overflow:hidden 裁剪。
        function repairBodyHeight(){
          if (!document.body || vh <= 0) return 'skip';
          var bh = document.body.getBoundingClientRect().height;
          // 高度达到视口一半以上视为正常；否则可能是视口单位失效，
          // 或 WebUI 在 WebView 完成布局前按旧的 innerHeight 算好了尺寸（如 qBittorrent）。
          if (bh >= vh * 0.5) return 'ok';
          // 该 WebView 里 dvh/vh 视口单位可能全部算成 0，百分比高度也随之失效，
          // 所以直接用 window.innerHeight 换算成像素写内联样式。
          if (document.documentElement) {
            document.documentElement.style.setProperty('height', vh + 'px', 'important');
          }
          document.body.style.setProperty('height', vh + 'px', 'important');
          document.body.style.setProperty('min-height', vh + 'px', 'important');
          document.body.style.setProperty('overflow', 'visible', 'important');
          // 仅当 body 完全塌陷时（如 PanSou 的 #app/#app-shell 都为 0），
          // 才需要兜底撑开布局容器；body 只是偏矮时（qBittorrent），
          // 撑 body 即可由 CSS 百分比自然传导，直接改子元素会把页头也拉满、
          // 把 pageWrapper 挤到屏幕外。
          if (bh === 0) {
            var nodes = document.querySelectorAll('body > *, body > * > *');
            for (var i = 0; i < nodes.length; i++) {
              var el = nodes[i], b = el.getBoundingClientRect();
              if (b.width >= vw * 0.9 && b.height === 0) {
                el.style.setProperty('height', vh + 'px', 'important');
                el.style.setProperty('min-height', vh + 'px', 'important');
                repaired.push((el.id || el.className || el.tagName).toString().slice(0, 40));
              }
            }
          }
          // 页面自己的 resize 处理可能抛错（qBittorrent/MooTools），不能影响诊断返回
          try { window.dispatchEvent(new Event('resize')); } catch (e) {}
          return document.body.getBoundingClientRect().height > 0 ? 'fixed' : 'fail';
        }
        // 该 WebView 里 vh/dvh 可能全部算成 0（实测 Android 16 / WebView 143）。
        // 受影响页面会把 height:100vh 的布局容器压成 0，只剩文字不出界面。
        function measureUnit(prop){
          var probe = document.createElement('div');
          probe.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;';
          document.documentElement.appendChild(probe);
          probe.style.height = prop;
          var value = probe.getBoundingClientRect().height;
          document.documentElement.removeChild(probe);
          return value;
        }
        function fixVhUnits(height){
          var count = 0;
          function convert(value){
            return value.replace(/([0-9.]+)(dvh|svh|lvh|vh)/g, function(m, num){
              return (parseFloat(num) * height / 100) + 'px';
            });
          }
          function walk(rules){
            for (var i = 0; i < rules.length; i++) {
              var r = rules[i];
              if (r.style) {
                var props = ['height', 'minHeight', 'maxHeight'];
                for (var p = 0; p < props.length; p++) {
                  var v = r.style[props[p]];
                  if (v && /(dvh|svh|lvh|vh)/.test(v)) {
                    try { r.style[props[p]] = convert(v); count++; } catch (e) {}
                  }
                }
              }
              // Chrome 支持 CSS 嵌套，CSSStyleRule 也有 cssRules，不能据此跳过 style
              if (r.cssRules && r.cssRules.length) walk(r.cssRules);
            }
          }
          for (var i = 0; i < document.styleSheets.length; i++) {
            try { var rules = document.styleSheets[i].cssRules; if (rules) walk(rules); } catch (e) {}
          }
          // 内联 style 里的 vh 也一并换算
          var inline = document.querySelectorAll('[style*="vh"]');
          for (var j = 0; j < inline.length; j++) {
            var props2 = ['height', 'minHeight', 'maxHeight'];
            for (var k = 0; k < props2.length; k++) {
              var iv = inline[j].style[props2[k]];
              if (iv && /(dvh|svh|lvh|vh)/.test(iv)) {
                try { inline[j].style[props2[k]] = convert(iv); count++; } catch (e) {}
              }
            }
          }
          return count;
        }
        var vhUnit = measureUnit('100vh');
        var vhFix = 0;
        if (vhUnit === 0) vhFix = fixVhUnits(vh);
        var bodyBroken = !!(document.body && vh > 0 && document.body.getBoundingClientRect().height < vh * 0.5);
        var bodyFix = repairBodyHeight();
        var e = document.elementFromPoint(vw / 2, vh / 2);
        return JSON.stringify({
          title: document.title || '',
          body: document.body ? document.body.innerText.length : 0,
          ready: document.readyState,
          scripts: document.scripts.length,
          width: vw,
          height: vh,
          bodyFix: bodyFix,
          repaired: repaired,
          vhFix: vhFix,
          vh: vhUnit,
          bodyRect: rectOf(document.body),
          desktop: (function(){ var d=document.getElementById('desktop'); if(!d) return null; var s=getComputedStyle(d),b=d.getBoundingClientRect(); return [s.display,s.visibility,s.opacity,b.x,b.y,b.width,b.height]; })(),
          atCenter: e ? (e.id || (typeof e.className === 'string' ? e.className : '') || e.tagName) : '',
          errors: (window.__ugErrors || []).slice(0, 5)
        });
      } catch (err) {
        return 'err:' + err;
      }
    })();
""".trimIndent()

