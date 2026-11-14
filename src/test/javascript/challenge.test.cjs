const assert = require('node:assert/strict');
const { createHash, webcrypto } = require('node:crypto');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const source = readFileSync(resolve(__dirname, '../../main/resources/challenge/files/script.js'), 'utf8');
const hash = text => createHash('sha256').update(text).digest('hex');

// Execute the shipped IIFE without exporting its internals or needing a browser dependency.
// Timers and clocks are controlled so failures cannot leave a PoW loop running in the background.
function browser(options = {}) {
    const timers = [];
    const requests = [];
    const images = [];
    const inputs = [];
    const status = { textContent: '', firstChild: { textContent: '' } };
    let click;
    let gate = false;
    let reloads = 0;
    let scheduledTimeouts = 0;
    let wall = 1700000000000;
    let monotonic = 100;
    const checkbox = { addEventListener(_event, handler) { click = handler; } };
    Object.defineProperty(status, 'innerHTML', { set() { gate = true; } });
    const document = {
        cookie: options.cookie || '',
        hidden: options.hidden || false,
        visibilityState: options.hidden ? 'hidden' : 'visible',
        getElementById(id) {
            if (id === 'status') return status;
            if (id === 'human-checkbox') return checkbox;
            if (id === 'human-gate' && gate) return { remove() { gate = false; } };
            return null;
        }
    };
    if (options.visibility) {
        delete document.hidden;
        delete document.visibilityState;
        Object.assign(document, options.visibility);
    }
    class XHR {
        constructor() { this.withCredentials = false; }
        open(method, url, async) {
            if (options.xhrThrows) throw new Error('XHR unavailable');
            Object.assign(this, { method, url, async });
        }
        send(body) { this.body = body; requests.push(this); }
    }
    const context = {
        document,
        CHALLENGE: options.challenge ?? 'm849b8541',
        TARGET_PREFIX: options.prefix ?? '888',
        MAX_ITERATIONS: options.maxIterations ?? 2000,
        ENDPOINT: '/.sensepitch.challenge.complete',
        STEP: '/.sensepitch.challenge.step',
        XMLHttpRequest: XHR,
        Image: class { set src(url) { images.push(url); } },
        location: { reload() { reloads++; } },
        Date: class extends Date { constructor() { super(wall); } },
        performance: options.noPerformance ? undefined : { now() { return monotonic; } },
        setTimeout(callback, delay) {
            scheduledTimeouts++;
            timers.push({ callback, delay });
        }
    };
    if (options.mode === 'native') context.crypto = webcrypto;
    if (options.mode === 'throw') context.crypto = { subtle: { digest() { throw new Error('unavailable'); } } };
    if (options.mode === 'reject') context.crypto = { subtle: { digest() { return Promise.reject(new Error('failed')); } } };
    if (options.mode === 'legacy' || options.mode === 'legacy-error') {
        context.msCrypto = { subtle: { digest(_algorithm, bytes) {
            const operation = {};
            timers.push({ delay: 0, callback() {
                if (options.mode === 'legacy-error') operation.onerror();
                else operation.oncomplete({ target: { result: Uint8Array.from(createHash('sha256').update(bytes).digest()).buffer } });
            } });
            return operation;
        } } };
    }
    if (options.mode === 'controlled') {
        context.crypto = { subtle: { digest(_algorithm, bytes) {
            inputs.push(Buffer.from(bytes).toString('utf8'));
            // Defer delivery like the actual WebCrypto API, but control the successful nonce.
            return { then(success) {
                timers.push({ delay: 0, callback() {
                    const result = new Uint8Array(32);
                    result[0] = inputs.length === options.solveAt + 1 ? 0xab : 0;
                    success(result.buffer);
                } });
            } };
        } } };
    }
    context.self = context;
    vm.runInNewContext(source, context, { filename: 'script.js', timeout: 1000 });
    return {
        requests, images, inputs, status, checkbox,
        get reloads() { return reloads; },
        get scheduledTimeouts() { return scheduledTimeouts; },
        click() { assert.ok(click); click(); },
        advance(ms, wallMs = ms) { monotonic += ms; wall += wallMs; },
        respond(code) {
            const request = requests.at(-1);
            request.status = code;
            request.readyState = 4;
            request.onreadystatechange();
        },
        async drain() {
            for (let steps = 0; steps < 30000; steps++) {
                await new Promise(resolve => setImmediate(resolve));
                const timer = timers.shift();
                if (timer) {
                    monotonic += options.timerMs || 0;
                    wall += options.timerMs || 0;
                    timer.callback();
                }
                if (requests.some(request => request.url.includes('challenge=')) ||
                    status.textContent.startsWith('Verification failed.')) return;
            }
            assert.fail('challenge did not terminate');
        }
    };
}

function completion(page) {
    const request = page.requests.find(request => request.url.includes('challenge='));
    assert.ok(request, 'completion request was sent');
    assert.equal(request.method, 'GET');
    assert.equal(request.async, true);
    assert.equal(request.withCredentials, true);
    assert.equal(request.body, null);
    return new URL(request.url, 'https://example.com').searchParams;
}

for (const mode of ['shim', 'native', 'legacy', 'throw']) {
    test(`${mode}: solves the server's example challenge and reloads only after acceptance`, async () => {
        const page = browser({ mode });
        await page.drain();
        const params = completion(page);
        assert.equal(params.get('challenge'), 'm849b8541');
        assert.equal(params.get('nonce'), '1658');
        assert.equal(params.get('method'), mode === 'shim' || mode === 'throw' ? 'shim' : 'native');
        assert.equal(page.reloads, 0);
        page.respond(200);
        assert.equal(page.reloads, 1);
        assert.equal(page.status.textContent, 'Verified! Passing on…');
    });
}

// These cover SHA-256 padding boundaries, multiple blocks, and each supported UTF-8 width.
for (const challenge of ['', 'a'.repeat(54), 'a'.repeat(55), 'a'.repeat(63), 'a'.repeat(64), 'a'.repeat(128), 'é€😀&=?']) {
    for (const mode of ['shim', 'native']) {
        test(`${mode}: SHA-256 vector ${JSON.stringify(challenge)}`, async () => {
            const page = browser({ mode, challenge, prefix: hash(challenge + '0'), maxIterations: 1 });
            await page.drain();
            const params = completion(page);
            assert.equal(params.get('nonce'), '0');
            assert.equal(params.get('challenge'), challenge);
        });
    }
}

for (const mode of ['shim', 'native', 'reject', 'legacy-error']) {
    test(`${mode}: iteration limit stops without submitting a failed proof`, async () => {
        const page = browser({ mode, prefix: 'impossible', maxIterations: 3 });
        await page.drain();
        assert.equal(page.requests.length, 0);
        assert.equal(page.reloads, 0);
        assert.equal(page.status.textContent, 'Verification failed. Please refresh.');
    });
}

test('yields after 10000 iterations and submits the first matching nonce', async () => {
    const page = browser({ mode: 'controlled', prefix: 'ab', solveAt: 10001, maxIterations: 10002, timerMs: 1 });
    await page.drain();
    assert.equal(completion(page).get('nonce'), '10001');
    assert.equal(page.inputs.length, 10002);
    assert.equal(page.inputs[0], 'm849b85410');
    assert.equal(page.inputs.at(-1), 'm849b854110001');
    assert.equal(page.scheduledTimeouts, 1, 'the PoW loop schedules a cooperative yield');
    assert.equal(completion(page).get('durationMs'), '10003', 'includes all digests and the yield');
});

for (const solveAt of [2, 3]) {
    test(`iteration limit 3 ${solveAt === 2 ? 'includes nonce 2' : 'excludes nonce 3'}`, async () => {
        const page = browser({ mode: 'controlled', prefix: 'ab', solveAt, maxIterations: 3 });
        await page.drain();
        assert.equal(page.inputs.length, 3);
        if (solveAt === 2) assert.equal(completion(page).get('nonce'), '2');
        else assert.equal(page.requests.length, 0);
    });
}

test('hidden tab reports its state and starts only once after the checkbox click', async () => {
    const page = browser({ hidden: true, prefix: '', maxIterations: 1 });
    assert.equal(page.requests.length, 1);
    const initial = new URL(page.requests[0].url, 'https://example.com').searchParams;
    assert.equal(initial.get('event'), 'init-hidden');
    assert.equal(initial.get('hidden'), '1');
    assert.equal(initial.get('vis'), 'hidden');
    page.click();
    page.click();
    assert.equal(page.checkbox.disabled, true);
    await page.drain();
    assert.equal(page.requests.length, 2);
    completion(page);
});

test('hidden state uses an image ping if XHR throws', () => {
    const page = browser({ hidden: true, xhrThrows: true });
    assert.equal(page.requests.length, 0);
    assert.equal(page.images.length, 1);
    assert.match(page.images[0], /event=init-hidden/);
});

for (const visibility of [{ webkitHidden: true, webkitVisibilityState: 'prerender' }, { msHidden: true }]) {
    test(`vendor visibility fallback: ${JSON.stringify(visibility)}`, () => {
        const page = browser({ visibility });
        const params = new URL(page.requests[0].url, 'https://example.com').searchParams;
        assert.equal(params.get('hidden'), '1');
        assert.equal(params.get('vis'), visibility.webkitVisibilityState || 'hidden');
    });
}

test('existing admission cookie skips the calculation', () => {
    const page = browser({ cookie: 'sptkn=token' });
    assert.equal(page.requests.length, 0);
    assert.equal(page.status.textContent, 'Challenge completed, cookie set.');
});

for (const code of [0, 400, 500]) {
    test(`HTTP ${code} does not reload`, async () => {
        const page = browser({ prefix: '' });
        await page.drain();
        page.respond(code);
        assert.equal(page.reloads, 0);
        assert.match(page.status.textContent, /^Verification failed\./);
    });
}

test('completion transport exception reports failure', async () => {
    const page = browser({ prefix: '', xhrThrows: true });
    await page.drain();
    assert.equal(page.reloads, 0);
    assert.match(page.status.textContent, /^Verification failed\./);
});

for (const mode of ['shim', 'native', 'legacy', 'throw']) {
    test(`${mode}: reports completion wall time and monotonic calculation duration`, async () => {
        const page = browser({ mode, prefix: '' });
        // A backwards wall-clock adjustment must not change a monotonic duration.
        page.advance(12.5, -1000);
        await page.drain();
        const params = completion(page);
        assert.equal(params.get('completedAt'), '1699999999000');
        assert.equal(params.get('durationMs'), '12.5');
        assert.equal(params.get('method'), mode === 'shim' || mode === 'throw' ? 'shim' : 'native');
        page.advance(500);
        page.respond(200);
        assert.equal(params.get('durationMs'), '12.5', 'network time is excluded');
    });
}

test('duration excludes the hidden-tab wait before calculation starts', async () => {
    const page = browser({ hidden: true, prefix: '' });
    page.advance(10000);
    page.click();
    page.advance(25);
    await page.drain();
    const params = completion(page);
    assert.equal(params.get('completedAt'), '1700000010025');
    assert.equal(params.get('durationMs'), '25');
});

for (const elapsed of [35, -35]) {
    test(`wall-clock fallback reports a nonnegative duration (${elapsed} ms)`, async () => {
        const page = browser({ noPerformance: true, prefix: '' });
        page.advance(elapsed);
        await page.drain();
        const params = completion(page);
        assert.equal(params.get('completedAt'), String(1700000000000 + elapsed));
        assert.equal(params.get('durationMs'), String(Math.max(0, elapsed)));
    });
}
