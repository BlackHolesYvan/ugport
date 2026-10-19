package com.ugport.web

import org.json.JSONObject

object LoginScripts {

    fun autoFill(username: String, password: String): String {
        val user = JSONObject.quote(username)
        val pass = JSONObject.quote(password)
        return """
            (function(){
              try {
                var U = $user, P = $pass;
                function setVal(el, v) {
                  if (!el) return;
                  var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                  var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                  if (desc && desc.set) { desc.set.call(el, v); } else { el.value = v; }
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                }
                var pw = document.querySelector('input[type=password]');
                if (!pw) return 'no-password-input';
                var inputs = Array.prototype.slice.call(document.querySelectorAll('input')).filter(function(i){
                  return i.type !== 'password' && i.offsetParent !== null && !i.disabled && !i.readOnly;
                });
                var userInput = inputs.filter(function(i){
                  return /user|account|name|账号|用户/i.test((i.name || '') + (i.id || '') + (i.placeholder || ''));
                })[0] || inputs[0];
                if (userInput) setVal(userInput, U);
                setVal(pw, P);
                var buttons = Array.prototype.slice.call(document.querySelectorAll('button, .i-btn, [role=button]'));
                var btn = buttons.filter(function(b){
                  return /登录|登錄|login|sign in/i.test((b.innerText || b.textContent || '').trim());
                })[0] || document.querySelector('button[type=submit]');
                if (btn) { btn.click(); return 'submitted'; }
                return 'filled';
              } catch (e) { return 'error:' + e; }
            })();
        """.trimIndent()
    }

    /** 登录页是 hash 路由，登录成功不一定触发 onPageFinished，用轮询兜底。 */
    fun tokenWatcher(): String = """
        (function(){
          if (window.__ugTokenWatch) return 'already';
          window.__ugTokenWatch = setInterval(function(){
            try {
              if (!window.UgBridge || typeof window.UgBridge.onToken !== 'function') return;
              var raw = localStorage.getItem('accessInfo');
              if (!raw) return;
              var o = JSON.parse(raw);
              var t = o && (o.api_token || o.token || o.static_token);
              if (t) {
                window.UgBridge.onToken(String(t));
                clearInterval(window.__ugTokenWatch);
                window.__ugTokenWatch = null;
              }
            } catch (e) {}
          }, 1000);
          return 'watching';
        })();
    """.trimIndent()

    fun extractToken(): String = """
        (function(){
          try {
            if (!window.UgBridge || typeof window.UgBridge.onToken !== 'function') return 'no-bridge';
            function emit(v) {
              if (v === null || v === undefined) return false;
              var s = String(v);
              if (s.length < 16) return false;
              window.UgBridge.onToken(s);
              return true;
            }
            var keys = ['accessInfo', 'proConfig', 'proUserInfo'];
            for (var i = 0; i < keys.length; i++) {
              var raw = localStorage.getItem(keys[i]);
              if (!raw) continue;
              try {
                var o = JSON.parse(raw);
                if (o && emit(o.api_token || o.token || o.static_token)) return 'ok';
                var n = o && (o.accessInfo || o.nasAccessInfo);
                if (n && emit(n.api_token || n.token)) return 'ok';
                if (o && o.data && emit(o.data.token)) return 'ok';
              } catch (e) {}
            }
            for (var k in localStorage) {
              if (!/token|access|uno|login/i.test(k)) continue;
              var v = localStorage.getItem(k);
              if (!v || v.length < 16 || v.charAt(0) === '{') continue;
              if (emit(v)) return 'ok';
            }
            return 'not-found';
          } catch (e) { return 'error:' + e; }
        })();
    """.trimIndent()
}
