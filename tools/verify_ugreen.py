#!/usr/bin/env python3
"""
用真实会话验证绿联 NAS 的登录态、GetUid 与端口 uid 拼接。

用法：
  python3 tools/verify_ugreen.py --token <明文token> --base https://xxx.ug.link --port-url https://app-8080-xxx.ugdocker.link
  python3 tools/verify_ugreen.py --har har/xxx.har --port-url https://app-8080-xxx.ugdocker.link

HAR 模式会从最新的 code/login 或 login 响应里读取 data.token。
注意：HAR 里的 token 可能已经过期（服务端一般返回 code 1024）。
"""
import argparse
import base64
import json
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
# 绿联 Web 客户端使用的公开标识，不是个人账号凭据。
CLIENT_ID = "7a2466ca-1b98-40a7-ae67-WEB"


def headers(base):
    return {
        "User-Agent": UA,
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Origin": base,
        "Referer": base + "/desktop/",
        "Client-Id": CLIENT_ID,
        "Client-Version": "78578",
        "Client-Version-Str": "1.20.0",
        "UG-Agent": "PC/WEB",
        "X-Specify-Language": "zh-CN",
    }


def request(url, method="GET", base=None, body=None, redirect=False, cookie=None):
    data = None
    h = headers(base or "")
    if cookie:
        h["Cookie"] = cookie
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    if redirect:
        opener = urllib.request.build_opener(urllib.request.HTTPRedirectHandler())
        resp = opener.open(req, timeout=25)
    else:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None
        opener = urllib.request.build_opener(NoRedirect)
        try:
            resp = opener.open(req, timeout=25)
        except urllib.error.HTTPError as e:
            resp = e
    raw = resp.read()
    text = raw.decode("utf-8", "replace")
    return resp.status if hasattr(resp, "status") else resp.code, dict(resp.headers), text


def token_from_har(path):
    with open(path, encoding="utf-8") as f:
        har = json.load(f)
    entries = har["log"]["entries"]
    token = None
    base = None
    for e in reversed(entries):
        url = e["request"]["url"]
        if "/ugreen/v1/verify/code/login" in url or url.endswith("/ugreen/v1/verify/login"):
            raw = e.get("response", {}).get("content", {}).get("text", "")
            if e["response"]["content"].get("encoding") == "base64":
                raw = base64.b64decode(raw).decode("utf-8", "replace")
            try:
                obj = json.loads(raw)
                data = obj.get("data") or {}
                token = data.get("token") or token
                if token:
                    base = "{0.scheme}://{0.netloc}".format(urllib.parse.urlsplit(url))
                    break
            except Exception:
                pass
    return token, base


def mask(value):
    if not value:
        return "<none>"
    return value[:6] + "..." + value[-4:] if len(value) > 12 else "***"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--token")
    ap.add_argument("--har")
    ap.add_argument("--base")
    ap.add_argument("--port-url")
    args = ap.parse_args()

    token = args.token
    base = args.base
    if args.har:
        token, har_base = token_from_har(args.har)
        base = base or har_base
    if not token or not base:
        sys.exit("需要 --token/--har 与 --base")

    token = token.strip()
    base = base.rstrip("/")
    print("base:", base)
    print("token:", mask(token))

    code, _, text = request(f"{base}/ugreen/v1/verify/is_login?token={urllib.parse.quote(token)}", base=base)
    print("is_login HTTP", code, "->", text[:300])
    try:
        ok = json.loads(text).get("code") == 200
    except Exception:
        ok = False
    if not ok:
        print("登录态无效或已过期，无法继续验证 uid。")
        return

    code, _, text = request(f"{base}/ugreen/v1/docker/app/GetUid?token={urllib.parse.quote(token)}", base=base)
    print("GetUid HTTP", code, "->", text[:500])
    uid = None
    try:
        data = json.loads(text).get("data") or {}
        uid = data.get("result") or data.get("uid")
    except Exception:
        pass
    if not uid:
        print("未拿到 uid。")
        return
    print("uid:", mask(str(uid)))

    if args.port_url:
        match = re.search(r"app-(\d+)-", args.port_url)
        if not match:
            print("无法从端口地址解析端口号")
            return
        port = match.group(1)
        code, _, text = request(
            f"{base}/ugreen/v1/gateway/proxy/dockerToken?port={port}&token={urllib.parse.quote(token)}",
            base=base,
        )
        print("dockerToken HTTP", code, "->", text[:300])
        redirect_url = None
        try:
            redirect_url = (json.loads(text).get("data") or {}).get("redirect_url")
        except Exception:
            pass
        if not redirect_url:
            return
        code, _, html = request(redirect_url, base=base)
        cookie = re.search(r"ugreen-proxy-token=([^;'\s]+)", html)
        if not cookie:
            print("auth 页面未找到 ugreen-proxy-token")
            return
        code, hdrs, text = request(
            args.port_url,
            base=base,
            cookie=f"ugreen-proxy-token={cookie.group(1)}",
        )
        title = re.search(r"<title>(.*?)</title>", text, re.S)
        print("port HTTP", code, "title:", title.group(1).strip() if title else "-", "len:", len(text))


if __name__ == "__main__":
    main()
