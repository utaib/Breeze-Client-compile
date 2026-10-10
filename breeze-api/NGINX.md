# nginx configuration for the Breeze API

## The upload size problem, and why it looks like a network error

**Symptom.** Uploading a cape fails with "network error" in the admin page and
"failed to fetch" in the launcher. Small capes work; larger ones do not.

**Cause.** nginx's `client_max_body_size` defaults to **1 MB**. The Breeze API
accepts up to 2 MB per file (multer's `fileSize` limit), so every upload between
1 MB and 2 MB is rejected by nginx *before Express ever sees it*.

Measured against the live API:

| upload size | result |
| --- | --- |
| 900 KB | 401 (reaches the app, auth rejected as expected) |
| 1000 KB | 401 (reaches the app) |
| **1100 KB** | **413 (nginx rejects it)** |
| 2 MB | 413 |

**Why it surfaces as a network error rather than a 413.** nginx serves its own
error page for 413, which never passes through the app's CORS middleware. A
cross-origin response with no `Access-Control-Allow-Origin` header is blocked by
the browser, so the client sees a failed fetch instead of a status code. The
real error is invisible to the frontend, which is why the message is useless.

## The fix

In the server block that proxies to the API, raise the limit to match what the
application actually accepts:

```nginx
server {
    server_name api.breezeclient.net;

    # Must be >= the app's own limit (multer fileSize is 2 MB per file, and a
    # cape upload can carry several files plus form fields). 8 MB leaves room
    # for animated capes without letting a mistaken video upload through.
    client_max_body_size 8M;

    # Large uploads on a slow connection otherwise trip the default 60s.
    client_body_timeout 120s;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Do not buffer the whole upload to disk before forwarding it.
        proxy_request_buffering off;
    }
}
```

Then:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

`nginx -t` first: a syntax error in a reload takes the API down.

## Make the 413 visible instead of silent

Even with the limit raised, a genuinely oversized upload should produce a
readable message rather than a blocked fetch. Add CORS headers to nginx's own
error response so the browser is allowed to read it:

```nginx
    # nginx generates this response itself, so the app's CORS middleware never
    # runs. Without these headers the browser blocks the response and the user
    # sees "failed to fetch" with no indication that the file was too large.
    error_page 413 = @too_large;
    location @too_large {
        add_header Access-Control-Allow-Origin  $http_origin always;
        add_header Access-Control-Allow-Credentials true      always;
        default_type application/json;
        return 413 '{"success":false,"error":"That file is too large. The limit is 8 MB."}';
    }
```

## Verifying

```bash
# Should return 401 (reaches the app), not 413.
head -c 2000000 /dev/zero > /tmp/probe.bin
curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST https://api.breezeclient.net/capes -F "file=@/tmp/probe.bin"
```

A 401 means nginx passed it through and the app rejected it for auth, which is
the correct outcome for an unauthenticated probe. A 413 means the limit is still
too low.
