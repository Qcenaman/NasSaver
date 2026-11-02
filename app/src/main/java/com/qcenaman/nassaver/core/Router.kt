package com.qcenaman.nassaver.core

import com.qcenaman.nassaver.core.api.Api
import com.qcenaman.nassaver.core.auth.Auth
import com.qcenaman.nassaver.core.dav.WebDav
import com.qcenaman.nassaver.core.http.Http
import com.qcenaman.nassaver.core.http.Request
import com.qcenaman.nassaver.core.http.Response

/**
 * "/" web page, "/dav/" WebDAV, "/api/" JSON for the page, "/status", "/health".
 * Until a login exists only the setup page works; after that everything but /health needs the login.
 */
class Router(
    private val auth: Auth,
    private val dav: WebDav,
    private val api: Api,
    private val page: () -> ByteArray,
    private val status: () -> String,
    private val log: (String) -> Unit = {},
) {
    fun handle(req: Request): Response {
        val p = req.path
        if (p == "/health") return Response.text(200, "ok\n")
        if (!auth.isConfigured) return setupOnly(req)

        when (auth.check(req.header("authorization"), req.remote)) {
            Auth.Result.OK -> Unit
            Auth.Result.LOCKED -> return Response.text(429, "Too many wrong passwords. Try again in a few minutes.\n")
                .header("Retry-After", (Auth.LOCK_MS / 1000).toString())
            Auth.Result.BAD, Auth.Result.MISSING -> return Response.text(401, "Login required\n")
                .header("WWW-Authenticate", "Basic realm=\"NasSaver\", charset=\"UTF-8\"")
        }

        if (req.method == "OPTIONS") return dav.options()
        return when {
            p == "/dav" || p.startsWith("/dav/") -> dav.handle(req, p.removePrefix("/dav").ifEmpty { "/" })
            p.startsWith("/api/") -> api.handle(req, p.removePrefix("/api"))
            // Windows probes the server root with PROPFIND before opening /dav.
            req.method == "PROPFIND" && p == "/" -> Response.xml(
                207,
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/</D:href>" +
                    "<D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>" +
                    "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>",
            )
            req.method != "GET" && req.method != "HEAD" -> Response.text(405, "method not allowed\n")
            p == "/" || p == "/index.html" -> Response(200, Response.Body.Bytes(page()))
                .header("Content-Type", "text/html; charset=utf-8").header("Cache-Control", "no-cache")
            p == "/status" -> Response.html(200, status())
            p == "/setup" -> Response.redirect("/")
            else -> Response.text(404, "not found\n")
        }
    }

    private fun setupOnly(req: Request): Response {
        val p = req.path
        return when {
            p == "/setup" && req.method == "POST" -> {
                val form = Http.parseQuery(req.bodyText(8 * 1024))
                val user = form["user"].orEmpty()
                val password = form["password"].orEmpty()
                val error = when {
                    password != form["confirm"].orEmpty() -> "The two passwords don't match."
                    else -> auth.setLogin(user, password)
                }
                if (error != null) Response.html(400, Pages.setup(error, user))
                else {
                    log("login created for \"${user.trim()}\" from ${req.remote}")
                    Response.html(200, Pages.setupDone())
                }
            }
            p == "/setup" -> Response.html(200, Pages.setup(null, ""))
            p == "/" || p == "/index.html" -> Response.redirect("/setup")
            else -> Response.text(503, "No login yet. Open this server's address in a browser to create one.\n")
        }
    }
}

object Pages {
    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title><style>
:root{color-scheme:light dark;--bg:#f6f7f9;--card:#fff;--fg:#111827;--muted:#6b7280;--line:#e5e7eb;--accent:#2563eb;--bad:#dc2626}
@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--card:#181b21;--fg:#e5e7eb;--muted:#9ca3af;--line:#2a2f37;--accent:#60a5fa;--bad:#f87171}}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:28px;width:min(380px,calc(100vw - 32px));box-sizing:border-box}
h1{font-size:22px;margin:0 0 6px}p{color:var(--muted);margin:0 0 18px}label{display:block;font-size:14px;margin:12px 0 4px}
input{width:100%;box-sizing:border-box;padding:10px 12px;border:1px solid var(--line);border-radius:8px;background:transparent;color:inherit;font:inherit}
button,a.btn{display:block;width:100%;margin-top:20px;padding:11px;border:0;border-radius:8px;background:var(--accent);color:#fff;font:inherit;font-weight:600;text-align:center;text-decoration:none;cursor:pointer}
.err{color:var(--bad);margin:0 0 8px}</style></head><body><div class="card">$body</div></body></html>"""

    fun setup(error: String?, user: String): String = page(
        "NasSaver setup",
        "<h1>Welcome to NasSaver</h1><p>Create the login that every device will use to open your TV's drives.</p>" +
            (if (error != null) "<p class=\"err\">${esc(error)}</p>" else "") +
            "<form method=\"post\" action=\"/setup\">" +
            "<label for=\"u\">Username</label><input id=\"u\" name=\"user\" autocomplete=\"username\" required value=\"${esc(user)}\">" +
            "<label for=\"p\">Password</label><input id=\"p\" name=\"password\" type=\"password\" autocomplete=\"new-password\" minlength=\"6\" required>" +
            "<label for=\"c\">Repeat password</label><input id=\"c\" name=\"confirm\" type=\"password\" autocomplete=\"new-password\" minlength=\"6\" required>" +
            "<button type=\"submit\">Create login</button></form>",
    )

    fun setupDone(): String = page(
        "NasSaver ready",
        "<h1>Login created</h1><p>Your browser will now ask for it once. To forget it later, use Reset login in the NasSaver app on the TV.</p>" +
            "<a class=\"btn\" href=\"/\">Open my drives</a>",
    )

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
