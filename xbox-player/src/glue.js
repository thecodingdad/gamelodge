// GameLodge's side of the Xbox player: starts xstreaming-player (unchanged) and carries its
// signaling and the controllers between it and GameLodge. GameLodge does all requests to Xbox
// Live itself; this page never sees a token.
//
// Transport: Windows (WebView2) window.chrome.webview, Android window.GameLodgeBridge; GameLodge
// answers through window.gameLodgeReceive(json) (Android) or WebView2 messages.
import xStreamingPlayer from 'xstreaming-player';

const post = (msg) => {
  const text = JSON.stringify(msg);
  if (window.chrome && window.chrome.webview) window.chrome.webview.postMessage(text);
  else if (window.GameLodgeBridge) window.GameLodgeBridge.postMessage(text);
};
const log = (message) => post({ t: 'log', message: String(message) });

let player = null;
let statsTimer = null;
let inputTimer = null;
let stopped = false;
// The controllers' latest state: sent to the console continuously (the console reads a steady
// stream of input frames, like the player's own gamepad driver sends; a lone frame gets lost).
let pads = [];

// The player reports rumble the way XStreaming's Android app takes it ("Device" mode).
window.ReactNativeWebView = {
  postMessage(text) {
    try {
      const m = JSON.parse(text);
      if (m.type !== 'deviceVibration' && m.type !== 'nativeVibration') return;
      const r = m.message.rumbleData;
      post({ t: 'rumble', index: 0, strong: r.strongMagnitude, weak: r.weakMagnitude, durationMs: r.duration });
    } catch (e) { /* not ours */ }
  },
};

// The video stays hidden (no browser placeholder) until its first frame is there; GameLodge shows
// its own start screen until then.
const NO_POSTER = 'data:image/gif;base64,R0lGODlhAQABAAAAACw=';
let shown = false;
let sharpness = 0;
let hint = '';

/** Shows the start notice for a few seconds once the picture is there. */
function showHint() {
  const el = document.getElementById('hint');
  if (!hint || !el) return;
  el.textContent = hint;
  el.classList.add('on');
  setTimeout(() => el.classList.remove('on'), 6000);
}
function watchVideo() {
  const holder = document.getElementById('videoHolder');
  const hook = (video) => {
    if (video.dataset.gl) return;
    video.dataset.gl = '1';
    video.poster = NO_POSTER;
    const live = () => {
      if (shown) return;
      shown = true;
      if (sharpness > 0) startSharpening(video);
      else video.style.visibility = 'visible';
      post({ t: 'video' });
      showHint();
    };
    if (video.requestVideoFrameCallback) video.requestVideoFrameCallback(() => live());
    video.addEventListener('playing', () => setTimeout(live, 150));
  };
  new MutationObserver(() => holder.querySelectorAll('video').forEach(hook)).observe(holder, { childList: true, subtree: true });
  holder.querySelectorAll('video').forEach(hook);
}

// FSR: the player draws the video sharpened into #canvas (WebGL); without WebGL the plain video shows.
function startSharpening(video) {
  try {
    player.startFSR(() => {});
    if (player._isFSR) {
      document.getElementById('fsr').style.display = 'flex';
      log('sharpening on (' + sharpness + ')');
      return;
    }
    log('sharpening not available');
  } catch (e) {
    log('FSR: ' + e);
  }
  video.style.visibility = 'visible';
}

function start(config) {
  sharpness = config.sharpness || 0;
  hint = config.hint || '';
  if (config.fitCss) document.documentElement.style.setProperty('--fit', config.fitCss);
  watchVideo();
  player = new xStreamingPlayer('videoHolder', {
    input_touch: false,
    ui_touchenabled: false,
    input_mousekeyboard: false,
    input_legacykeyboard: false,
  });
  player.bind();
  player.setCodecPreferences('video/H264', { profiles: ['4d'] });
  // Controllers come from GameLodge (queued below), never from the browser's gamepad API.
  player.setGamepadKernal('Native');
  player.setGamepadIndex(0);
  player.setVibration(config.vibration !== false);
  player.setVibrationMode('Device');
  player.setVideoFormat(config.fit || '');
  if (sharpness > 0) player.setFsrSharpness(sharpness);
  if (config.maxBitrateKbps > 0) player.setVideoBitrate(config.maxBitrateKbps);
  player.setConnectFailHandler(() => post({ t: 'state', state: 'failed', detail: 'No connection to the console (NAT)' }));
  player.setSdpHandler((client, offer) => post({ t: 'chatOffer', sdp: offer.sdp }));
  player.getEventBus().on('connectionstate', (e) => {
    post({ t: 'state', state: e.state });
    if (e.state === 'connected' && !inputTimer) {
      inputTimer = setInterval(() => {
        const input = player && player.getChannelProcessor('input');
        if (input && pads.length) input.queueGamepadStates(pads);
      }, 4);
    }
    if (e.state === 'connected' && !statsTimer) {
      statsTimer = setInterval(async () => {
        try { post(Object.assign({ t: 'stats' }, await player.getStreamState())); } catch (err) { /* stream ending */ }
      }, 2000);
    }
  });
  player.createOffer()
    .then((offer) => post({ t: 'offer', sdp: offer.sdp }))
    .catch((e) => post({ t: 'state', state: 'failed', detail: 'Offer: ' + e }));
}

function stop() {
  if (stopped) return;
  stopped = true;
  if (statsTimer) clearInterval(statsTimer);
  if (inputTimer) clearInterval(inputTimer);
  try { if (player) player.close(); } catch (e) { /* already closed */ }
}

function receive(text) {
  const m = typeof text === 'string' ? JSON.parse(text) : text;
  switch (m.t) {
    case 'config': start(m); break;
    case 'answer':
      player.setRemoteOffer(m.sdp);
      post({ t: 'ice', candidates: player.getIceCandidates().map((c) => ({ candidate: c.candidate, sdpMLineIndex: c.sdpMLineIndex, sdpMid: c.sdpMid })) });
      break;
    case 'chatAnswer': player.setRemoteOffer(m.sdp); break;
    case 'remoteIce': player.setIceCandidates(m.candidates); break;
    case 'pads': {
      pads = m.pads;
      // A change goes out at once, not only with the next tick.
      const input = player && player.getChannelProcessor('input');
      if (input) input.queueGamepadStates(pads);
      break;
    }
    case 'stop': stop(); break;
    default: break;
  }
}

window.gameLodgeReceive = (text) => { try { receive(text); } catch (e) { log('receive: ' + e); } };
if (window.chrome && window.chrome.webview) {
  window.chrome.webview.addEventListener('message', (e) => window.gameLodgeReceive(e.data));
}
window.addEventListener('error', (e) => log('error: ' + e.message));
post({ t: 'ready' });
