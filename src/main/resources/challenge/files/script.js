(function () {
    var status = document.getElementById('status');

    // --- CONFIG ---
    var YIELD_EVERY = 10000; // iterations per chunk

    // --- UTIL: visibility with vendor fallbacks ---
    function docHidden() {
        if (typeof document.hidden !== 'undefined') return document.hidden;
        if (typeof document.webkitHidden !== 'undefined') return document.webkitHidden;
        if (typeof document.msHidden !== 'undefined') return document.msHidden;
        // If unsupported, assume visible
        return false;
    }

    function visState() {
        return document.visibilityState ||
            document.webkitVisibilityState ||
            (docHidden() ? "hidden" : "visible");
    }

    // --- Transport cascade ---
    function sendOnce(baseUrl, params, withCreds) {
        var qs = [];
        for (var key in params) if (params.hasOwnProperty(key)) {
            qs.push(encodeURIComponent(key) + "=" + encodeURIComponent(params[key]));
        }
        var url = baseUrl + (baseUrl.indexOf("?") === -1 ? "?" : "&") + qs.join("&");

        // 1) try sendBeacon (best for background/unload)
        if (navigator.sendBeacon) {
            var body = qs.join("&");
            if (navigator.sendBeacon(baseUrl + "?" + body, new Blob([body], { type: "application/x-www-form-urlencoded;charset=UTF-8" }))) {
                return;
            }
        }

        // 2) fallback: XHR
        try {
            var xhr = new XMLHttpRequest();
            xhr.open("GET", url, true);
            if (withCreds === true) xhr.withCredentials = true;
            xhr.send(null);
            return;
        } catch (e) {}

        // 3) fallback: image ping
        try {
            var img = new Image();
            img.src = url;
            setTimeout(function () { img = null; }, 5000);
        } catch (e2) {}
    }

    function onVisibilityChange(handler) {
        if (typeof document.addEventListener !== 'function') return;
        var ev = 'visibilitychange';
        if (typeof document.hidden === 'undefined') {
            if (typeof document.webkitHidden !== 'undefined') ev = 'webkitvisibilitychange';
            else if (typeof document.msHidden !== 'undefined') ev = 'msvisibilitychange';
        }
        document.addEventListener(ev, handler, true);
    }

    // --- UTIL: UTF-8 encode (TextEncoder-free) -> Uint8Array ---
    function utf8Bytes(str) {
        // Simple UTF-8 encoder
        var bytes = [], i = 0, c;
        for (i = 0; i < str.length; i++) {
            c = str.charCodeAt(i);
            if (c < 0x80) bytes.push(c);
            else if (c < 0x800) {
                bytes.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
            } else if (c >= 0xD800 && c <= 0xDBFF) {
                // surrogate pair
                var c2 = str.charCodeAt(++i);
                var code = ((c & 0x3FF) << 10) | (c2 & 0x3FF);
                code += 0x10000;
                bytes.push(
                    0xF0 | (code >> 18),
                    0x80 | ((code >> 12) & 0x3F),
                    0x80 | ((code >> 6) & 0x3F),
                    0x80 | (code & 0x3F)
                );
            } else {
                bytes.push(
                    0xE0 | (c >> 12),
                    0x80 | ((c >> 6) & 0x3F),
                    0x80 | (c & 0x3F)
                );
            }
        }
        return new Uint8Array(bytes);
    }

    // --- UTIL: hex string from bytes ---
    function toHex(u8) {
        var hex = '';
        for (var i = 0; i < u8.length; i++) {
            var h = u8[i].toString(16);
            if (h.length < 2) h = '0' + h;
            hex += h;
        }
        return hex;
    }

    // Minimal, reasonably fast pure-JS SHA-256 (public-domain style)
    // Adapted to avoid typed-array dependencies beyond Uint8Array for inputs.
    function sha256HexSync(ascii) {
        var bytes = utf8Bytes(ascii);
        var i, j, l;

        var K = [
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
        ];

        // Pre-processing (padding)
        var ml = bytes.length;
        var withOne = new Uint8Array(ml + 1); // append 0x80
        for (i = 0; i < ml; i++) withOne[i] = bytes[i];
        withOne[ml] = 0x80;

        // Calculate required length (multiple of 64, leaving 8 bytes for bit length)
        var totalLen = withOne.length;
        while ((totalLen % 64) !== 56) totalLen++;
        var padded = new Uint8Array(totalLen + 8);
        for (i = 0; i < withOne.length; i++) padded[i] = withOne[i];

        // Append 64-bit big-endian length
        var bitLen = ml * 8;
        for (i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = bitLen & 0xFF;
            bitLen = Math.floor(bitLen / 256);
        }

        var H0 = 0x6a09e667, H1 = 0xbb67ae85, H2 = 0x3c6ef372, H3 = 0xa54ff53a;
        var H4 = 0x510e527f, H5 = 0x9b05688c, H6 = 0x1f83d9ab, H7 = 0x5be0cd19;

        var W = new Array(64);
        for (i = 0; i < padded.length; i += 64) {
            for (j = 0; j < 16; j++) {
                var idx = i + j * 4;
                W[j] = (padded[idx] << 24) | (padded[idx + 1] << 16) | (padded[idx + 2] << 8) | (padded[idx + 3]);
            }
            for (j = 16; j < 64; j++) {
                var s0 = ror(W[j - 15], 7) ^ ror(W[j - 15], 18) ^ (W[j - 15] >>> 3);
                var s1 = ror(W[j - 2], 17) ^ ror(W[j - 2], 19) ^ (W[j - 2] >>> 10);
                W[j] = (W[j - 16] + s0 + W[j - 7] + s1) | 0;
            }

            var a = H0, b = H1, c = H2, d = H3, e = H4, f = H5, g = H6, h = H7;

            for (j = 0; j < 64; j++) {
                var S1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25);
                var ch = (e & f) ^ (~e & g);
                var temp1 = (h + S1 + ch + K[j] + W[j]) | 0;
                var S0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22);
                var maj = (a & b) ^ (a & c) ^ (b & c);
                var temp2 = (S0 + maj) | 0;

                h = g; g = f; f = e; e = (d + temp1) | 0;
                d = c; c = b; b = a; a = (temp1 + temp2) | 0;
            }

            H0 = (H0 + a) | 0;
            H1 = (H1 + b) | 0;
            H2 = (H2 + c) | 0;
            H3 = (H3 + d) | 0;
            H4 = (H4 + e) | 0;
            H5 = (H5 + f) | 0;
            H6 = (H6 + g) | 0;
            H7 = (H7 + h) | 0;
        }

        return (
            hex32(H0) + hex32(H1) + hex32(H2) + hex32(H3) +
            hex32(H4) + hex32(H5) + hex32(H6) + hex32(H7)
        );

        function ror(x, n) { return (x >>> n) | (x << (32 - n)); }
        function hex32(x) {
            var s = (x >>> 0).toString(16);
            while (s.length < 8) s = '0' + s;
            return s;
        }
    }

    function sha256Hex(str, cb) {
        try {
            var subtle = (self.crypto && self.crypto.subtle) || (self.msCrypto && self.msCrypto.subtle);
            if (subtle && typeof subtle.digest === 'function') {
                var data = utf8Bytes(str);
                var p = subtle.digest({ name: 'SHA-256' }, data);
                if (typeof p.then === 'function') {
                    p.then(function (buf) { cb(null, toHex(new Uint8Array(buf))); },
                        function (err) { cb(err || new Error('digest failed')); });
                    return;
                }
                // old msCrypto: use events (already async)
                p.oncomplete = function (e) { cb(null, toHex(new Uint8Array(e.target.result))); };
                p.onerror = function () { cb(new Error('digest failed')); };
                return;
            }
        } catch (e) { /* fall through */ }

        // Shim path (pure JS) — force async delivery to avoid stack growth:
        setTimeout(function () {
            cb(null, sha256HexSync(str));
        }, 0);
    }

    // --- PoW loop (chunked; cooperative) ---
    function runPoW(done) {
        status.textContent = 'Testing browser…';
        var nonce = 0;
        function chunk() {
            var iterations = 0;
            function step() {
                sha256Hex(CHALLENGE + nonce, function (err, hex) {
                    if (!err && hex && hex.indexOf(TARGET_PREFIX) === 0) {
                        return done(null, nonce);
                    }
                    nonce++;
                    iterations++;
                    if (iterations >= YIELD_EVERY) {
                        status.textContent = status.textContent + '.';
                        // Yield to the event loop
                        setTimeout(chunk, 0);
                    } else {
                        step();
                    }
                });
            }
            step();
        }
        chunk();
    }


    // --- Network: XHR with credentials ---
    function httpGetWithCreds(url, cb) {
        try {
            var xhr = new XMLHttpRequest();
            if ('withCredentials' in xhr) {
                xhr.open('GET', url, true);
                xhr.withCredentials = true;
                xhr.onreadystatechange = function () {
                    if (xhr.readyState === 4) {
                        // status 0 can occur for opaque/cors issues; treat as failure
                        var ok = (xhr.status >= 200 && xhr.status < 300);
                        cb(null, ok);
                    }
                };
                xhr.onerror = function () { cb(null, false); };
                xhr.send(null);
            } else {
                // XDomainRequest (old IE) could go here, but cookies + CORS are tricky.
                cb(null, false);
            }
        } catch (e) {
            cb(null, false);
        }
    }

    // --- UI gate for hidden tabs ---
    function renderGate() {
        if (document.getElementById('human-gate')) return;
        status.innerHTML = ''
            + '<div>To continue, please verify you are human.</div>'
            + '<label id="human-gate" class="gate">'
            + '  <input id="human-checkbox" type="checkbox" />'
            + '  <span>Please click to verify that you are a human</span>'
            + '</label>'
            + '<div class="muted">A browser verification will start after you click the checkbox</div>';

        var cb = document.getElementById('human-checkbox');
        var clicked = false;
        cb.addEventListener('click', function () {
            if (clicked) return;
            clicked = true;
            cb.disabled = true;
            cb.checked = true;
            status.firstChild.textContent = 'Thanks! Starting verification…';
            startFlow();
        }, { once: true });
    }

    var started = false;
    function startFlow() {
        if (started) return;
        started = true;
        var gate = document.getElementById('human-gate');
        if (gate) gate.remove();

        // Solve PoW, then canvas, then request
        runPoW(function (err, nonce) {
            if (err) {
                status.textContent = 'Verification failed. Please refresh.';
                return;
            }
            status.textContent = 'Requesting access…';
            var qs = 'challenge=' + encodeURIComponent(CHALLENGE)
                + '&nonce=' + encodeURIComponent(nonce);
            httpGetWithCreds(ENDPOINT + '?' + qs, function (_err, ok) {
                if (ok) {
                    status.textContent = 'Verified! Passing on…';
                    try { location.reload(); } catch (_e2) { location.href = location.href; }
                } else {
                    status.textContent = 'Verification failed. Please refresh or return to the tab and try again.';
                }
            });
        });
    }

    // --- Boot ---
    // If cookie already present, bail out early
    if (document.cookie && document.cookie.indexOf('sptkn=') !== -1) {
        status.textContent = 'Challenge completed, cookie set.';
        return;
    }

    // If initially hidden, show gate; else start
    if (docHidden()) {
        sendOnce(STEP, { hidden: docHidden() ? 1 : 0, vis: visState(), event: "init-hidden" }, true);
        renderGate();
    } else {
        status.textContent = 'Checking your browser…';
        startFlow();
    }

// If it becomes visible before clicking, start automatically (remove this if you want click to be mandatory)
//    onVisibilityChange(function () {
//        if (!docHidden() && !started) {
//            status.textContent = 'Checking your browser…';
//            startFlow();
//        }
//    });

})();

