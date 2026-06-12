const WebSocket = require('ws');
const https = require('https');
const fs = require('fs');

process.on('uncaughtException', (err) => {
  console.error('UNCAUGHT:', err.message, err.stack);
});

const DASHBOARD_HTML = fs.readFileSync('/home/ubuntu/android-remote-monitor/app/src/main/res/raw/dashboard.html', 'utf8');
const WS_URL = 'wss://screen-mirror-relay-quod.onrender.com/agent';
const DEVICE_ID = 'dev_pwtest_v2';
const BOUNDARY = '--frame';
const RELAY_BASE = 'https://screen-mirror-relay-quod.onrender.com';

function fakeJpeg() {
  return Buffer.from('/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtRAAECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYI4Q/SFhSRFJiMkVic4EzQjR0RSlFNkVUcCZS/2gAMAwEAAEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYI4Q/SFhSRFJiMkVic4EzQjR0RSlFNkVUcCZS/2gAMAwEAAEDEQEQA8AA2gD/2Q==', 'base64');
}

let registeredUrl = null;
let ws = null;
let reqCount = 0;

function connect() {
  ws = new WebSocket(WS_URL);

  ws.on('open', () => {
    console.log('WS: open register...');
    ws.send(JSON.stringify({ type: 'register', name: 'PwTest', device_id: DEVICE_ID }));
  });

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch(e) { return; }

    if (msg.type === 'assigned') {
      registeredUrl = msg.url;
      console.log('URL:' + registeredUrl);
      return;
    }

    if (msg.type === 'request') {
      reqCount++;
      console.log('REQ#' + reqCount + ': ' + msg.method + ' ' + msg.path);
      handleRequest(msg);
    }
  });

  ws.on('close', (code, reason) => {
    console.log('WS: close code=' + code + ' reason=' + (reason || ''));
    ws = null;
    setTimeout(connect, 2000);
  });

  ws.on('error', (err) => {
    console.error('WS: error:', err.message);
  });
}

function handleRequest(msg) {
  const { id, path } = msg;

  if (path === '/' || path === '') {
    console.log('  -> HTML ' + DASHBOARD_HTML.length + 'b');
    const data = Buffer.from(DASHBOARD_HTML, 'utf8').toString('base64');
    ws.send(JSON.stringify({ type: 'response_headers', id, status: 200,
      headers: { 'Content-Type': 'text/html' }, streaming: false }));
    ws.send(JSON.stringify({ type: 'response_data', id, data, last: true }));
    return;
  }

  if (path.startsWith('/stream')) {
    console.log('  -> MJPEG');
    ws.send(JSON.stringify({ type: 'response_headers', id, status: 200,
      headers: { 'Content-Type': 'multipart/x-mixed-replace; boundary=' + BOUNDARY,
        'Cache-Control': 'no-cache' }, streaming: true }));
    const jpeg = fakeJpeg();
    const header = '--' + BOUNDARY + '\r\nContent-Type: image/jpeg\r\nContent-Length: ' + jpeg.length + '\r\n\r\n';
    const frame = Buffer.concat([Buffer.from(header, 'utf8'), jpeg, Buffer.from('\r\n', 'utf8')]);
    ws.send(JSON.stringify({ type: 'response_data', id, data: frame.toString('base64'), last: false }));
    return;
  }

  const data = Buffer.from(JSON.stringify({ ok: true }), 'utf8').toString('base64');
  ws.send(JSON.stringify({ type: 'response_headers', id, status: 200,
    headers: { 'Content-Type': 'application/json' }, streaming: false }));
  ws.send(JSON.stringify({ type: 'response_data', id, data, last: true }));
}

connect();

setInterval(() => {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.ping();
  }
}, 5000);
