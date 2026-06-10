/* ============================================================
   pi-manager — frontend (wired to the real backend API)
   Views: fleet overview / device detail
   Temps réel : SSE (/api/stream). Pas de WebSocket.
   ============================================================ */

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const el = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };

/* --- inline icon set (stroke, currentColor) --- */
const ICN = {
  search: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg>',
  terminal: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m5 8 4 4-4 4"/><path d="M13 16h6"/></svg>',
  arrow: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg>',
  back: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5"/><path d="m12 19-7-7 7-7"/></svg>',
  upload: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5-5 5 5"/><path d="M12 5v12"/></svg>',
  copy: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>',
  tv: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><rect x="2" y="5" width="20" height="13" rx="2"/><path d="m8 21 4-3 4 3"/></svg>',
  amphi: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M3 10 12 4l9 6"/><path d="M5 9v8M19 9v8M3 20h18M9 13v4M15 13v4"/></svg>',
  conf: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>',
  music: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/></svg>',
  cache: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/></svg>',
  power: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/></svg>',
  reboot: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/></svg>',
  refresh: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 0 1 15-6.7L21 8M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-15 6.7L3 16M3 21v-5h5"/></svg>',
  trash: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>',
  prev: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>',
  next: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>',
  minus: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12h14"/></svg>',
  plus: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M12 5v14M5 12h14"/></svg>',
  clock: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>',
  warn: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 9v4M12 17h.01"/><path d="M10.3 3.9 2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/></svg>',
  check: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M20 6 9 17l-5-5"/></svg>',
  info: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/></svg>',
  x: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M18 6 6 18M6 6l12 12"/></svg>',
  file: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>',
  inbox: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M22 12h-6l-2 3h-4l-2-3H2"/><path d="M5.5 5.1 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.8 4H7.2a2 2 0 0 0-1.7 1.1z"/></svg>',
  unplug: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="m19 5 3-3M2 22l3-3M6.3 20.3a2.4 2.4 0 0 0 3.4 0L12 18l-6-6-2.3 2.3a2.4 2.4 0 0 0 0 3.4Z"/><path d="m18 12 2.3-2.3a2.4 2.4 0 0 0 0-3.4l-2.6-2.6a2.4 2.4 0 0 0-3.4 0L12 6"/></svg>',
};

const DISPLAY_TYPES = {
  amphi: { label: 'Amphi', icon: ICN.amphi },
  conf:  { label: 'Conférence', icon: ICN.conf },
  tv:    { label: 'TV hall', icon: ICN.tv },
};

const STATE_META = {
  ready: { label: 'Ready', cls: 'ready' },
  setup: { label: 'Setup in progress', cls: 'setup' },
  new:   { label: 'New', cls: 'new' },
  off:   { label: 'Not connected', cls: 'off' },
};

/* backend state slug -> frontend state key */
const STATE_KEY = {
  'ready': 'ready',
  'setup in progress': 'setup',
  'new': 'new',
  'not connected': 'off',
};

/* message affiché quand on tente de piloter un contrôle de l'appli d'affichage */
const DEFERRED_MSG = "L'appli d'affichage n'est pas encore déployée — contrôle en lecture seule.";

/* ============================================================
   API CLIENT
   ============================================================ */
async function api(path, { method = 'GET', body, multipart } = {}) {
  const opts = { method };
  if (multipart) {
    opts.body = multipart;
  } else if (body !== undefined) {
    opts.headers = { 'Content-Type': 'application/json' };
    opts.body = JSON.stringify(body);
  }
  const res = await fetch('/api' + path, opts);
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const err = new Error((data && (data.error || data.message)) || `HTTP ${res.status}`);
    err.status = res.status;
    err.details = data && data.details;
    throw err;
  }
  return data;
}

/* ============================================================
   ADAPTERS (API shape -> view model)
   ============================================================ */
function looksLikeIp(s) { return /^\d{1,3}(\.\d{1,3}){3}$/.test(s || ''); }

function normalizeDisplay(v) {
  if (!v) return null;
  const k = String(v).toLowerCase();
  if (k.startsWith('amphi') || k === 'auditorium') return 'amphi';
  if (k.startsWith('conf')) return 'conf';
  if (k === 'tv' || k.includes('hall')) return 'tv';
  return DISPLAY_TYPES[k] ? k : null;
}

function relTime(iso) {
  if (!iso) return 'jamais';
  const then = new Date(iso).getTime();
  if (isNaN(then)) return iso;
  const diff = Math.max(0, Date.now() - then) / 1000;
  if (diff < 5) return "à l'instant";
  if (diff < 60) return `il y a ${Math.floor(diff)} s`;
  if (diff < 3600) return `il y a ${Math.floor(diff / 60)} min`;
  if (diff < 86400) return `il y a ${Math.floor(diff / 3600)} h`;
  return `il y a ${Math.floor(diff / 86400)} j`;
}

function adapt(d) {
  const st = d.status || {};
  const state = STATE_KEY[d.state] || 'off';
  const ip = st.ip || (looksLikeIp(d.host) ? d.host : '—');
  const hostname = st.hostname || (looksLikeIp(d.host) ? '—' : d.host);
  return {
    id: d.id,
    name: d.name,
    host: d.host,
    sshUser: d.sshUser || 'pi',
    state,
    rawState: d.state,
    display: normalizeDisplay(st.displayType || d.displayType),
    music: st.music ? !!st.music.enabled : false,
    cache: st.pageCache && st.pageCache.status === 'stale' ? 'stale' : 'ok',
    ip,
    hostname,
    mac: st.mac || '—',
    uptime: st.uptime || '—',
    lastseen: relTime(d.lastCheckedAt),
    progress: typeof st.progress === 'number' ? st.progress : null,
    step: st.step || st.message || 'Installation en cours…',
    slide: st.slide ? {
      idx: st.slide.current || 1, total: st.slide.total || 1,
      title: st.slide.title || '', dwell: st.slide.dwell || 15,
    } : null,
    appVersion: st.appVersion || d.appVersion || null,
    sshCommand: d.sshCommand || `ssh ${d.sshUser || 'pi'}@${d.host}`,
    lastError: d.lastError || null,
    files: (d.files || []).map(f => ({ id: f.id, name: f.name, size: fmtSize(f.size), date: relTime(f.modifiedAt) })),
  };
}

function fmtSize(bytes) {
  if (bytes == null) return '';
  if (bytes < 1024) return `${bytes} o`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} Ko`;
  return `${(bytes / 1024 / 1024).toFixed(1)} Mo`;
}

function fmtDur(s) {
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60), r = s % 60;
  return r ? `${m} min ${String(r).padStart(2, '0')} s` : `${m} min`;
}

/* ============================================================
   STATE
   ============================================================ */
const root = $('#app');
let DEVICES = [];                                  // adapted overviews
let DETAIL = null;                                 // adapted detail (current device)
const pushState = new Map();                       // "deviceId:fileId" -> 'proc'|'done'|'err'
let view = { name: 'fleet', deviceId: null, filter: 'all', typeFilter: 'all', q: '', status: 'data' };

/* ============================================================
   helpers — badge, toast, modal
   ============================================================ */
function badge(state) {
  const m = STATE_META[state] || STATE_META.off;
  return `<span class="badge badge--${m.cls}"><span class="badge__dot"></span>${m.label}</span>`;
}

function toast(title, msg, kind = 'ok') {
  const icon = kind === 'ok' ? ICN.check : kind === 'err' ? ICN.x : ICN.info;
  const t = el(`<div class="toast toast--${kind}">
    <span class="toast__icon">${icon}</span>
    <div class="toast__body"><div class="toast__title">${esc(title)}</div>${msg ? `<div class="toast__msg">${esc(msg)}</div>` : ''}</div>
  </div>`);
  $('#toasts').appendChild(t);
  setTimeout(() => { t.classList.add('is-out'); setTimeout(() => t.remove(), 260); }, 3600);
}

function esc(s) { return String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

function confirmAction({ title, text, confirmLabel, danger, onConfirm }) {
  const ov = el(`<div class="overlay">
    <div class="modal" role="dialog" aria-modal="true">
      <div class="modal__head">
        ${danger ? `<span class="modal__warn">${ICN.warn}</span>` : ''}
        <div><div class="modal__title">${esc(title)}</div></div>
      </div>
      <div class="modal__text">${text}</div>
      <div class="modal__foot">
        <button class="btn btn--ghost" data-cancel>Annuler</button>
        <button class="btn ${danger ? 'btn--danger' : 'btn--primary'}" data-ok>${esc(confirmLabel)}</button>
      </div>
    </div>
  </div>`);
  const close = () => ov.remove();
  ov.addEventListener('click', (e) => { if (e.target === ov) close(); });
  $('[data-cancel]', ov).onclick = close;
  $('[data-ok]', ov).onclick = () => { close(); onConfirm && onConfirm(); };
  document.body.appendChild(ov);
  $('[data-ok]', ov).focus();
}

/* add-device modal */
function openAddDevice() {
  const ov = el(`<div class="overlay">
    <div class="modal modal--wide" role="dialog" aria-modal="true">
      <div class="modal__head"><div><div class="modal__title">Ajouter un Raspberry Pi</div></div></div>
      <div class="modal__body">
        <div class="steps-card">
          <div class="steps-card__t">Préparer un device vierge</div>
          <ol class="steps">
            <li>Flashe une image vierge <b>Raspberry Pi OS Lite</b> sur la carte SD avec <b>Raspberry Pi Imager</b>.</li>
            <li>Active <b>SSH</b> et branche le Pi sur le réseau de l'événement (Ethernet conseillé).</li>
            <li>Renseigne son identité ci-dessous — pi-manager l'enrôle dans le parc.</li>
          </ol>
        </div>
        <div class="form">
          <div class="field">
            <label for="f-host">Hostname ou IP</label>
            <input class="input mono" id="f-host" placeholder="rpi-salle-elixir.local" autocomplete="off">
            <div class="field__err hidden" id="f-host-err">Indique un hostname ou une IP.</div>
          </div>
          <div class="field-row">
            <div class="field"><label for="f-name">Nom (optionnel)</label><input class="input" id="f-name" placeholder="déduit du hostname" autocomplete="off"></div>
            <div class="field"><label for="f-user">Utilisateur SSH</label><input class="input mono" id="f-user" value="pi" autocomplete="off"></div>
          </div>
          <div class="setting__hint">pi-manager réutilise vos clés SSH (<span class="mono">~/.ssh</span>) — aucune saisie de mot de passe.</div>
        </div>
      </div>
      <div class="modal__foot">
        <button class="btn btn--ghost" data-cancel>Annuler</button>
        <button class="btn btn--primary" data-ok>Ajouter au parc</button>
      </div>
    </div>
  </div>`);
  const close = () => ov.remove();
  ov.addEventListener('click', (e) => { if (e.target === ov) close(); });
  $('[data-cancel]', ov).onclick = close;
  $('[data-ok]', ov).onclick = async () => {
    const host = $('#f-host', ov).value.trim();
    const user = $('#f-user', ov).value.trim() || 'pi';
    if (!host) { $('#f-host', ov).classList.add('is-invalid'); $('#f-host-err', ov).classList.remove('hidden'); $('#f-host', ov).focus(); return; }
    const name = $('#f-name', ov).value.trim() || host.replace(/\.local$/, '');
    try {
      await api('/devices', { method: 'POST', body: { name, host, sshUser: user } });
      close();
      toast('Device ajouté', `${name} · en attente de vérification`, 'ok');
      await loadFleet();
    } catch (e) {
      toast('Échec de l\'ajout', e.message, 'err');
    }
  };
  document.body.appendChild(ov);
  $('#f-host', ov).focus();
}

/* configuration — enrôle un Pi vierge : demande le mot de passe SSH (non stocké),
   pose la clé publique + dépose pi-swarm.json côté backend */
function openConfigure(d) {
  const ov = el(`<div class="overlay">
    <div class="modal" role="dialog" aria-modal="true">
      <div class="modal__head"><div><div class="modal__title">Configurer ${esc(d.name)}</div></div></div>
      <div class="modal__body">
        <div class="setting__hint" style="margin-bottom:14px">
          Ce Pi est <b>en ligne</b> mais n'a jamais été configuré. pi-manager va y déposer
          sa clé SSH et un fichier <span class="mono">pi-swarm.json</span>. Saisis le mot de
          passe du compte <span class="mono">${esc(d.sshUser)}</span> — il sert
          <b>uniquement</b> à cette opération et <b>n'est pas conservé</b>.
        </div>
        <div class="field">
          <label for="f-pass">Mot de passe SSH</label>
          <input class="input mono" id="f-pass" type="password" autocomplete="off" placeholder="••••••••">
          <div class="field__err hidden" id="f-pass-err">Saisis le mot de passe SSH.</div>
        </div>
      </div>
      <div class="modal__foot">
        <button class="btn btn--ghost" data-cancel>Annuler</button>
        <button class="btn btn--primary" data-ok>Configurer</button>
      </div>
    </div>
  </div>`);
  const close = () => ov.remove();
  ov.addEventListener('click', (e) => { if (e.target === ov) close(); });
  $('[data-cancel]', ov).onclick = close;
  const submit = async () => {
    const pass = $('#f-pass', ov).value;
    if (!pass) { $('#f-pass', ov).classList.add('is-invalid'); $('#f-pass-err', ov).classList.remove('hidden'); $('#f-pass', ov).focus(); return; }
    const okBtn = $('[data-ok]', ov);
    okBtn.disabled = true; okBtn.textContent = 'Configuration…';
    try {
      const r = await api(`/devices/${d.id}/configure`, { method: 'POST', body: { password: pass } });
      close();
      toast(r.ok ? 'Configuration réussie' : 'Configuration échouée', r.message || r.error || '', r.ok ? 'ok' : 'err');
      await loadFleet();
      if (view.name === 'detail' && view.deviceId === d.id) openDetail(d.id);
    } catch (e) {
      okBtn.disabled = false; okBtn.textContent = 'Configurer';
      toast('Configuration échouée', e.message, 'err');
    }
  };
  $('[data-ok]', ov).onclick = submit;
  $('#f-pass', ov).addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
  document.body.appendChild(ov);
  $('#f-pass', ov).focus();
}

/* ssh — ouvre un terminal sur le Mac via le backend */
async function launchSSH(d) {
  try {
    await api(`/devices/${d.id}/ssh/open`, { method: 'POST' });
    toast('Connexion SSH lancée', `${d.sshCommand} · terminal ouvert`, 'info');
  } catch (e) {
    toast('Échec ouverture terminal', e.message, 'err');
  }
}

/* ============================================================
   NAVIGATION
   ============================================================ */
function go(next) {
  Object.assign(view, next);
  render();
  window.scrollTo({ top: 0 });
}

function render() {
  if (view.name === 'detail') return renderDetail();
  return renderFleet();
}

/* ============================================================
   FLEET OVERVIEW
   ============================================================ */
function counts() {
  return {
    ready: DEVICES.filter(d => d.state === 'ready').length,
    setup: DEVICES.filter(d => d.state === 'setup' || d.state === 'new').length,
    off: DEVICES.filter(d => d.state === 'off').length,
  };
}

function appbar() {
  const c = counts();
  return `<header class="appbar">
    <div class="brandmark">
      <span class="brandmark__dot">π</span>
      <span class="brandmark__name">pi-manager <span>· Caen.tech</span></span>
    </div>
    <div class="appbar__spacer"></div>
    <div class="counters">
      <div class="counter"><span class="counter__dot" style="background:var(--st-ready)"></span><span class="counter__num">${c.ready}</span><span class="counter__lbl">ready</span></div>
      <div class="counter"><span class="counter__dot" style="background:var(--st-setup)"></span><span class="counter__num">${c.setup}</span><span class="counter__lbl">en setup</span></div>
      <div class="counter"><span class="counter__dot" style="background:var(--st-off)"></span><span class="counter__num">${c.off}</span><span class="counter__lbl">offline</span></div>
    </div>
  </header>`;
}

function toolbar() {
  const stateChips = [
    ['all', 'Tous', null],
    ['ready', 'Ready', 'var(--st-ready)'],
    ['setup', 'Setup', 'var(--st-setup)'],
    ['new', 'New', 'var(--st-new)'],
    ['off', 'Offline', 'var(--st-off)'],
  ].map(([k, l, c]) => `<button class="chip ${view.filter === k ? 'is-active' : ''}" data-filter="${k}">${c ? `<span class="chip__dot" style="background:${c}"></span>` : ''}${l}</button>`).join('');

  const typeChips = [
    ['all', 'Tous types'],
    ['amphi', 'Amphi'],
    ['conf', 'Conférence'],
    ['tv', 'TV hall'],
  ].map(([k, l]) => `<button class="chip ${view.typeFilter === k ? 'is-active' : ''}" data-type="${k}">${l}</button>`).join('');

  return `<div class="toolbar">
    <label class="search">${ICN.search}<input type="text" id="q" placeholder="Rechercher nom, IP, hostname…" value="${esc(view.q)}"></label>
    <div class="filter-group">${stateChips}</div>
    <div class="filter-group">${typeChips}</div>
    <div class="toolbar__spacer"></div>
    <button class="btn btn--ghost" id="refresh">${ICN.refresh} Actualiser</button>
  </div>`;
}

function deviceCard(d) {
  const dt = d.display ? DISPLAY_TYPES[d.display] : null;
  let body = '';

  if (d.state === 'ready') {
    body = `
      <div class="params">
        ${dt ? `<span class="param">${dt.icon}<b>${dt.label}</b></span>` : ''}
        <span class="param ${d.music ? 'param--on' : 'param--off-val'}">${ICN.music}musique <b>${d.music ? 'on' : 'off'}</b></span>
        <span class="param ${d.cache === 'ok' ? '' : 'param--warn'}">${ICN.cache}cache <b>${d.cache === 'ok' ? 'à jour' : 'périmé'}</b></span>
      </div>
      <div class="identity">
        <div class="identity__row"><span class="identity__k">IP</span><span class="identity__v">${esc(d.ip)}</span></div>
        <div class="identity__row"><span class="identity__k">host</span><span class="identity__v">${esc(d.hostname)}</span></div>
      </div>`;
  } else if (d.state === 'setup') {
    const pct = d.progress != null ? d.progress + '%' : '…';
    body = `
      <div class="progress">
        <div class="progress__meta"><span>Installation en cours</span><b>${pct}</b></div>
        <div class="progress__track"><div class="progress__fill" style="width:${d.progress != null ? d.progress : 45}%"></div></div>
        <div class="progress__steps">${esc(d.step)}</div>
      </div>
      <div class="identity">
        <div class="identity__row"><span class="identity__k">IP</span><span class="identity__v">${esc(d.ip)}</span></div>
        <div class="identity__row"><span class="identity__k">host</span><span class="identity__v">${esc(d.hostname)}</span></div>
      </div>`;
  } else if (d.state === 'new') {
    body = `
      <div class="newprompt">En ligne mais non configuré. Lance la <b>Configuration</b> pour poser la clé SSH et l'enrôler dans le parc.</div>
      <div class="identity">
        <div class="identity__row"><span class="identity__k">host</span><span class="identity__v">${esc(d.hostname)}</span></div>
        <div class="identity__row"><span class="identity__k">MAC</span><span class="identity__v">${esc(d.mac)}</span></div>
      </div>`;
  } else {
    body = `
      <div class="lastseen">${ICN.clock} Dernière connexion <b>${esc(d.lastseen)}</b></div>
      <div class="identity">
        <div class="identity__row"><span class="identity__k">IP</span><span class="identity__v">${esc(d.ip)}</span></div>
        <div class="identity__row"><span class="identity__k">host</span><span class="identity__v">${esc(d.hostname)}</span></div>
      </div>`;
  }

  let foot = '';
  if (d.state === 'ready') {
    foot = `
      <button class="btn btn--ssh btn--sm" data-ssh="${d.id}" title="Ouvrir un terminal SSH">${ICN.terminal} SSH</button>
      <div class="minidrop" data-drop="${d.id}">${ICN.upload} Déposer</div>
      <button class="btn btn--sm" data-open="${d.id}">Détail ${ICN.arrow}</button>`;
  } else if (d.state === 'setup') {
    foot = `<button class="btn btn--ssh btn--sm" data-ssh="${d.id}">${ICN.terminal} SSH</button>
      <div class="toolbar__spacer" style="flex:1"></div>
      <button class="btn btn--sm" data-open="${d.id}">Suivre ${ICN.arrow}</button>`;
  } else if (d.state === 'new') {
    foot = `<button class="btn btn--primary btn--sm" data-setup="${d.id}">Configuration</button>
      <div class="toolbar__spacer" style="flex:1"></div>
      <button class="btn btn--sm" data-open="${d.id}">Détail ${ICN.arrow}</button>`;
  } else {
    foot = `<button class="btn btn--ssh btn--sm" disabled>${ICN.terminal} SSH</button>
      <div class="toolbar__spacer" style="flex:1"></div>
      <button class="btn btn--sm" data-open="${d.id}">Détail ${ICN.arrow}</button>`;
  }

  return `<article class="card ${d.state === 'off' ? 'card--off' : ''}">
    <div class="card__head">
      <div style="flex:1;min-width:0"><div class="card__name">${esc(d.name)}</div>${dt ? `<div class="card__name-sub">${dt.label.toLowerCase()}</div>` : ''}</div>
      ${badge(d.state)}
    </div>
    ${body}
    <div class="card__foot">${foot}</div>
  </article>`;
}

function addDeviceCard() {
  return `<button class="card card--add" id="add-device">
    <span class="card-add__icon">${ICN.plus}</span>
    <span class="card-add__label">Ajouter un Raspberry</span>
    <span class="card-add__sub">Flasher &amp; enrôler un nouveau Pi</span>
  </button>`;
}

function filteredFleet() {
  const q = view.q.trim().toLowerCase();
  return DEVICES.filter(d => {
    if (view.filter !== 'all' && d.state !== view.filter) return false;
    if (view.typeFilter !== 'all' && d.display !== view.typeFilter) return false;
    if (q && !(`${d.name} ${d.ip} ${d.hostname}`.toLowerCase().includes(q))) return false;
    return true;
  });
}

function renderFleet() {
  let content;
  if (view.status === 'loading') {
    content = `<div class="grid">${Array(6).fill(0).map(() => `
      <div class="skeleton-card">
        <div class="sk sk--line" style="width:55%"></div>
        <div class="sk sk--line" style="width:35%;margin-bottom:20px"></div>
        <div class="sk sk--line" style="width:90%"></div>
        <div class="sk sk--line" style="width:80%"></div>
        <div class="sk" style="height:34px;width:100%;margin-top:24px"></div>
      </div>`).join('')}</div>`;
  } else if (view.status === 'error') {
    content = `<div class="statebox statebox--error">
      <div class="statebox__icon">${ICN.unplug}</div>
      <div class="statebox__title">Impossible de joindre le contrôleur</div>
      <div class="statebox__text">L'API d'orchestration ne répond pas. Vérifie que le backend pi-manager tourne (port 10028).</div>
      <button class="btn btn--primary" id="retry">${ICN.refresh} Réessayer</button>
    </div>`;
  } else {
    const list = filteredFleet();
    if (DEVICES.length === 0) {
      content = `<div class="statebox">
        <div class="statebox__icon">${ICN.inbox}</div>
        <div class="statebox__title">Aucun device enregistré</div>
        <div class="statebox__text">Le parc est vide. Ajoute un premier Raspberry Pi pour démarrer.</div>
        <button class="btn btn--primary" id="add-empty">${ICN.plus} Ajouter un Raspberry</button>
      </div>`;
    } else if (list.length === 0) {
      content = `<div class="statebox">
        <div class="statebox__icon">${ICN.inbox}</div>
        <div class="statebox__title">Aucun device ne correspond</div>
        <div class="statebox__text">Aucun Raspberry Pi ne correspond à ta recherche ou à tes filtres. Réinitialise pour voir tout le parc.</div>
        <button class="btn" id="clear-filters">Réinitialiser les filtres</button>
      </div>`;
    } else {
      content = `<div class="grid">${list.map(deviceCard).join('')}${addDeviceCard()}</div>`;
    }
  }

  root.innerHTML = `<div class="app">${appbar()}${toolbar()}${content}</div>`;
  bindFleet();
}

function bindFleet() {
  const q = $('#q');
  if (q) {
    q.oninput = (e) => {
      view.q = e.target.value;
      renderFleet();
      const i = $('#q'); if (i) { i.focus(); const v = i.value; i.setSelectionRange(v.length, v.length); }
    };
  }
  $$('[data-filter]').forEach(b => b.onclick = () => { view.filter = b.dataset.filter; renderFleet(); });
  $$('[data-type]').forEach(b => b.onclick = () => { view.typeFilter = b.dataset.type; renderFleet(); });
  $('#refresh') && ($('#refresh').onclick = () => refreshFleet());
  $('#retry') && ($('#retry').onclick = () => loadFleet());
  $('#clear-filters') && ($('#clear-filters').onclick = () => { view.filter = 'all'; view.typeFilter = 'all'; view.q = ''; renderFleet(); });
  $('#add-empty') && ($('#add-empty').onclick = openAddDevice);
  $('#add-device') && ($('#add-device').onclick = openAddDevice);
  $$('[data-open]').forEach(b => b.onclick = () => openDetail(b.dataset.open));
  $$('[data-ssh]').forEach(b => b.onclick = (e) => { e.stopPropagation(); launchSSH(DEVICES.find(d => d.id === b.dataset.ssh)); });
  $$('[data-setup]').forEach(b => b.onclick = () => { const d = DEVICES.find(x => x.id === b.dataset.setup); if (d) openConfigure(d); });
  bindMiniDrops();
}

function bindMiniDrops() {
  $$('[data-drop]').forEach(z => {
    z.onclick = () => pickAndUpload(z.dataset.drop, true);
    ['dragover', 'dragenter'].forEach(ev => z.addEventListener(ev, e => { e.preventDefault(); z.classList.add('is-drag'); }));
    ['dragleave', 'drop'].forEach(ev => z.addEventListener(ev, e => { e.preventDefault(); z.classList.remove('is-drag'); }));
    z.addEventListener('drop', e => { if (e.dataTransfer.files.length) uploadFiles(z.dataset.drop, e.dataTransfer.files, true); });
  });
}

/* ============================================================
   ACTIONS (real API calls)
   ============================================================ */
async function refreshFleet() {
  go({ status: 'loading' });
  try {
    await Promise.allSettled(DEVICES.map(d => api(`/devices/${d.id}/check`, { method: 'POST' })));
  } catch (e) { /* ignore individual failures */ }
  await loadFleet();
}

async function runSetup(id) {
  toast('Setup lancé', 'Installation du fichier de statut…', 'info');
  try {
    const r = await api(`/devices/${id}/setup`, { method: 'POST' });
    toast(r.ok ? 'Setup terminé' : 'Setup échoué', r.message || '', r.ok ? 'ok' : 'err');
  } catch (e) {
    toast('Setup échoué', e.message, 'err');
  }
  await loadFleet();
  if (view.name === 'detail' && view.deviceId === id) openDetail(id);
}

function pickAndUpload(deviceId, push) {
  const input = $('#hidden-file');
  input.value = '';
  input.onchange = () => { if (input.files.length) uploadFiles(deviceId, input.files, push); };
  input.click();
}

async function uploadFiles(deviceId, fileList, push) {
  const fd = new FormData();
  for (const f of fileList) fd.append('file', f);
  try {
    const saved = await api(`/devices/${deviceId}/files`, { multipart: fd });
    toast('Fichier déposé', `${saved.length} fichier(s) sur le contrôleur`, 'ok');
    if (push) for (const s of saved) await pushFileSilent(deviceId, s.id);
    if (view.name === 'detail' && view.deviceId === deviceId) openDetail(deviceId);
  } catch (e) {
    toast('Échec de l\'upload', e.message, 'err');
  }
}

async function pushFileSilent(deviceId, fileId) {
  pushState.set(`${deviceId}:${fileId}`, 'proc');
  try {
    const r = await api(`/devices/${deviceId}/files/${fileId}/push`, { method: 'POST' });
    pushState.set(`${deviceId}:${fileId}`, r.ok ? 'done' : 'err');
  } catch (e) {
    pushState.set(`${deviceId}:${fileId}`, 'err');
  }
}

async function pushFile(deviceId, fileId) {
  pushState.set(`${deviceId}:${fileId}`, 'proc');
  if (view.name === 'detail') renderDetail();
  try {
    const r = await api(`/devices/${deviceId}/files/${fileId}/push`, { method: 'POST' });
    pushState.set(`${deviceId}:${fileId}`, r.ok ? 'done' : 'err');
    toast(r.ok ? 'Fichier déployé' : 'Déploiement échoué', r.message || '', r.ok ? 'ok' : 'err');
  } catch (e) {
    pushState.set(`${deviceId}:${fileId}`, 'err');
    toast('Déploiement échoué', e.message, 'err');
  }
  if (view.name === 'detail') renderDetail();
}

async function deleteFile(deviceId, fileId, name) {
  try {
    await api(`/devices/${deviceId}/files/${fileId}`, { method: 'DELETE' });
    pushState.delete(`${deviceId}:${fileId}`);
    toast('Fichier supprimé', name, 'ok');
    if (view.name === 'detail') openDetail(deviceId);
  } catch (e) {
    toast('Suppression échouée', e.message, 'err');
  }
}

/* ============================================================
   DEVICE DETAIL
   ============================================================ */
async function openDetail(id) {
  view.name = 'detail';
  view.deviceId = id;
  root.innerHTML = `<div class="app"><button class="backlink" id="back-loading">${ICN.back} Vue flotte</button><div class="statebox"><div class="sk sk--line" style="width:200px;height:18px;margin:40px auto"></div></div></div>`;
  $('#back-loading').onclick = () => go({ name: 'fleet' });
  try {
    const detail = await api(`/devices/${id}`);
    DETAIL = adapt(detail);
    renderDetail();
    loadLogs(id);
  } catch (e) {
    toast('Device introuvable', e.message, 'err');
    go({ name: 'fleet' });
  }
}

function renderDetail() {
  const d = DETAIL;
  if (!d) return;
  const dt = d.display ? DISPLAY_TYPES[d.display] : null;

  const idGrid = `<div class="idgrid">
    <div class="idgrid__cell"><div class="idgrid__k">Adresse IP</div><div class="idgrid__v">${esc(d.ip)}</div></div>
    <div class="idgrid__cell"><div class="idgrid__k">Hostname</div><div class="idgrid__v">${esc(d.hostname)}</div></div>
    <div class="idgrid__cell"><div class="idgrid__k">MAC</div><div class="idgrid__v">${esc(d.mac)}</div></div>
    <div class="idgrid__cell"><div class="idgrid__k">Uptime</div><div class="idgrid__v">${esc(d.uptime)}</div></div>
    <div class="idgrid__cell" style="grid-column:1/-1"><div class="idgrid__k">Dernière vérification</div><div class="idgrid__v">${esc(d.lastseen)}</div></div>
  </div>`;

  const sshBlock = `<div class="ssh-block">
    <div class="panel__title" style="margin-bottom:12px">${ICN.terminal} Connexion rapide</div>
    <div class="ssh-cmd"><span class="ssh-cmd__prompt mono">$</span><code>${esc(d.sshCommand)}</code></div>
    <div class="ssh-actions">
      <button class="btn btn--ssh btn--block" id="ssh-launch" ${d.state === 'off' ? 'disabled' : ''}>${ICN.terminal} Lancer la session SSH</button>
      <button class="btn btn--icon" id="ssh-copy" title="Copier la commande">${ICN.copy}</button>
    </div>
    ${d.state === 'off' ? '<div class="setting__hint" style="margin-top:10px">Device hors ligne — connexion indisponible.</div>' : ''}
  </div>`;

  /* settings panel — display-app controls are READ-ONLY (app not deployed yet) */
  let settings = '';
  if (d.state === 'ready') {
    const seg = Object.entries(DISPLAY_TYPES).map(([key, m]) =>
      `<button class="${d.display === key ? 'is-active' : ''}" data-disp="${key}">${m.icon}${m.label}</button>`).join('');
    const slide = d.slide || { idx: 1, total: 1, title: '—', dwell: 15 };
    settings = `<div class="panel">
      <div class="panel__title">Paramètres d'affichage <span class="count">· lecture seule</span></div>
      <div class="setting__hint" style="margin:-6px 0 8px">${DEFERRED_MSG}</div>
      <div class="setting">
        <div><div class="setting__label">Musique d'ambiance</div><div class="setting__hint">Lecture audio sur la sortie HDMI</div></div>
        <div class="toggle ${d.music ? 'is-on' : ''}" id="music" role="switch" aria-checked="${d.music}"></div>
      </div>
      <div class="setting">
        <div><div class="setting__label">Type d'affichage</div><div class="setting__hint">Gabarit de mise en page du Pi</div></div>
        <div class="segmented" id="dispsel">${seg}</div>
      </div>
      <div class="setting">
        <div><div class="setting__label">Cache de page</div>
          <div class="cache-state" style="margin-top:4px"><span class="cache-state__dot" style="background:${d.cache === 'ok' ? 'var(--st-ready)' : 'var(--st-setup)'}"></span>${d.cache === 'ok' ? 'À jour' : 'Périmé — rebuild conseillé'}</div>
        </div>
        <button class="btn btn--sm" data-deferred>${ICN.refresh} Vider le cache</button>
      </div>
      <div class="setting">
        <div><div class="setting__label">Slide courant</div><div class="setting__hint">${esc(slide.title || 'Diapositive affichée')}</div></div>
        <div class="slidectl">
          <button class="btn btn--icon btn--sm" data-deferred title="Précédent">${ICN.prev}</button>
          <span class="slidectl__cur"><b>${slide.idx}</b>/${slide.total}</span>
          <button class="btn btn--icon btn--sm" data-deferred title="Suivant">${ICN.next}</button>
        </div>
      </div>
      <div class="setting">
        <div><div class="setting__label">Durée par slide</div><div class="setting__hint">Cycle ${fmtDur((slide.dwell || 15) * (slide.total || 1))}</div></div>
        <div class="slidectl">
          <button class="btn btn--icon btn--sm" data-deferred title="−5 s">${ICN.minus}</button>
          <span class="slidectl__cur" style="min-width:58px;text-align:center"><b>${slide.dwell || 15}</b> s</span>
          <button class="btn btn--icon btn--sm" data-deferred title="+5 s">${ICN.plus}</button>
        </div>
      </div>
    </div>`;
  } else if (d.state === 'setup') {
    const pct = d.progress != null ? d.progress + '%' : '…';
    settings = `<div class="panel">
      <div class="panel__title">Installation en cours</div>
      <div class="progress">
        <div class="progress__meta"><span>${esc(d.step)}</span><b>${pct}</b></div>
        <div class="progress__track"><div class="progress__fill" style="width:${d.progress != null ? d.progress : 45}%"></div></div>
      </div>
      <div class="setting__hint" style="margin-top:14px">Les contrôles d'affichage seront disponibles une fois le setup terminé.</div>
    </div>`;
  } else if (d.state === 'new') {
    settings = `<div class="panel">
      <div class="panel__title">Device non configuré</div>
      <div class="setting__hint" style="margin-bottom:14px">Ce Raspberry Pi est en ligne mais n'a pas encore été enrôlé. La configuration y dépose la clé SSH de pi-manager et le fichier <span class="mono">pi-swarm.json</span>.</div>
      <button class="btn btn--primary" id="run-setup">Configuration</button>
    </div>`;
  } else {
    settings = `<div class="panel">
      <div class="panel__title">Device hors ligne</div>
      <div class="statebox" style="padding:30px 10px">
        <div class="statebox__icon">${ICN.unplug}</div>
        <div class="statebox__title" style="font-size:15px">Pas de connexion</div>
        <div class="statebox__text" style="margin-bottom:0">Dernière connexion ${esc(d.lastseen)}. Les paramètres seront disponibles dès le retour en ligne.</div>
      </div>
    </div>`;
  }

  const actions = `<div class="panel">
    <div class="panel__title">Actions device</div>
    <div class="actions-row">
      ${d.state === 'new'
        ? `<button class="btn btn--primary btn--block" id="configure">Configuration</button>`
        : `<button class="btn" id="restart-app" ${d.state === 'off' ? 'disabled' : ''}>${ICN.refresh} Redémarrer l'affichage</button>
      <button class="btn btn--danger" id="reboot" ${d.state === 'off' ? 'disabled' : ''}>${ICN.reboot} Redémarrer</button>
      <button class="btn btn--danger" id="poweroff" ${d.state === 'off' ? 'disabled' : ''}>${ICN.power} Éteindre</button>`}
    </div>
  </div>`;

  const fileRows = d.files.map(f => {
    const stat = pushState.get(`${d.id}:${f.id}`);
    const pill = stat === 'done' ? '<span class="fstat fstat--done">Déployé</span>'
      : stat === 'proc' ? '<span class="fstat fstat--proc">Déploiement…</span>'
      : stat === 'err' ? '<span class="fstat fstat--err">Échec</span>' : '';
    return `<div class="fileitem">
      <span class="fileitem__icon">${ICN.file}</span>
      <div class="fileitem__main"><div class="fileitem__name">${esc(f.name)}</div><div class="fileitem__meta">${esc(f.size)} · ${esc(f.date)}</div></div>
      ${pill}
      <button class="btn btn--sm" data-push="${f.id}" ${d.state === 'off' ? 'disabled' : ''} title="Déployer sur le Pi">${ICN.arrow} Déployer</button>
      <button class="btn btn--icon btn--sm" data-del="${f.id}" data-name="${esc(f.name)}" title="Supprimer">${ICN.trash}</button>
    </div>`;
  }).join('');
  const dropbox = `<div class="panel">
    <div class="panel__title">Dropbox <span class="count">· ${d.files.length} fichier${d.files.length > 1 ? 's' : ''}</span></div>
    <div class="dropzone" id="dropzone">
      <div class="dropzone__icon">${ICN.upload}</div>
      <div><b>Glisse des fichiers ici</b> ou clique pour parcourir</div>
      <div class="dropzone__sub">Images, PDF, médias — déployés sur le Pi pour l'affichage</div>
    </div>
    ${fileRows ? `<div class="filelist">${fileRows}</div>` : ''}
  </div>`;

  const logs = `<div class="panel">
    <div class="panel__title">${ICN.clock} Journal d'état récent <span class="count" id="logs-src"></span></div>
    <div class="logs" id="logs"><div class="logline"><span class="logline__msg">Chargement des logs…</span></div></div>
  </div>`;

  root.innerHTML = `<div class="app">
    <button class="backlink" id="back">${ICN.back} Vue flotte</button>
    <div class="detail__head">
      <div style="flex:1">
        <div class="row"><div class="detail__title">${esc(d.name)}</div>${badge(d.state)}</div>
        <div class="detail__head-meta">${dt ? dt.label + ' · ' : ''}<span class="mono">${esc(d.hostname)}</span>${d.appVersion ? ' · v' + esc(d.appVersion) : ''}</div>
      </div>
    </div>
    <div class="detail">
      <div class="detail__main">
        ${settings}
        ${dropbox}
        ${logs}
      </div>
      <div class="detail__side">
        ${sshBlock}
        <div class="panel"><div class="panel__title">Identité technique</div>${idGrid}</div>
        ${actions}
      </div>
    </div>
  </div>`;
  bindDetail(d);
}

function bindDetail(d) {
  $('#back').onclick = () => go({ name: 'fleet' });

  $('#ssh-launch') && ($('#ssh-launch').onclick = () => launchSSH(d));
  $('#ssh-copy') && ($('#ssh-copy').onclick = async () => {
    try { await navigator.clipboard.writeText(d.sshCommand); toast('Commande copiée', d.sshCommand, 'ok'); }
    catch (e) { toast('Copie indisponible', d.sshCommand, 'info'); }
  });

  /* display-app controls are deferred: show a toast, change nothing */
  const music = $('#music');
  if (music) music.onclick = () => toast('Contrôle indisponible', DEFERRED_MSG, 'info');
  $$('[data-disp]').forEach(b => b.onclick = () => toast('Contrôle indisponible', DEFERRED_MSG, 'info'));
  $$('[data-deferred]').forEach(b => b.onclick = () => toast('Contrôle indisponible', DEFERRED_MSG, 'info'));
  $('#restart-app') && ($('#restart-app').onclick = () => toast('Contrôle indisponible', DEFERRED_MSG, 'info'));

  $('#run-setup') && ($('#run-setup').onclick = () => openConfigure(d));
  $('#configure') && ($('#configure').onclick = () => openConfigure(d));

  $('#reboot') && ($('#reboot').onclick = () => confirmAction({
    title: 'Redémarrer le device ?',
    text: `<code>${esc(d.name)}</code> sera injoignable pendant ~1 min le temps du reboot complet.`,
    confirmLabel: 'Redémarrer', danger: true,
    onConfirm: () => deviceAction(d.id, 'reboot', 'Redémarrage lancé'),
  }));
  $('#poweroff') && ($('#poweroff').onclick = () => confirmAction({
    title: 'Éteindre le device ?',
    text: `<code>${esc(d.name)}</code> sera mis hors tension. Un redémarrage physique sera nécessaire pour le rallumer.`,
    confirmLabel: 'Éteindre', danger: true,
    onConfirm: () => deviceAction(d.id, 'shutdown', 'Extinction demandée'),
  }));

  /* dropzone */
  const dz = $('#dropzone');
  if (dz) {
    dz.onclick = () => pickAndUpload(d.id, false);
    ['dragover', 'dragenter'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.add('is-drag'); }));
    ['dragleave', 'drop'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.remove('is-drag'); }));
    dz.addEventListener('drop', e => { if (e.dataTransfer.files.length) uploadFiles(d.id, e.dataTransfer.files, false); });
  }

  $$('[data-push]').forEach(b => b.onclick = () => pushFile(d.id, b.dataset.push));
  $$('[data-del]').forEach(b => b.onclick = () => confirmAction({
    title: 'Supprimer le fichier ?',
    text: `<code>${esc(b.dataset.name)}</code> sera retiré du contrôleur.`,
    confirmLabel: 'Supprimer', danger: true,
    onConfirm: () => deleteFile(d.id, b.dataset.del, b.dataset.name),
  }));
}

async function deviceAction(id, action, okMsg) {
  try {
    const r = await api(`/devices/${id}/actions/${action}`, { method: 'POST' });
    toast(r.ok ? okMsg : 'Action échouée', r.message || '', r.ok ? 'info' : 'err');
  } catch (e) {
    toast('Action échouée', e.message, 'err');
  }
}

const LOG_LVL = ['ok', 'info', 'warn', 'err'];
function detectLvl(line) {
  const l = line.toLowerCase();
  if (/\b(err|error|fail|échec|timeout)\b/.test(l)) return 'err';
  if (/\b(warn|périmé|stale)\b/.test(l)) return 'warn';
  if (/\b(ok|done|started|déployé|terminé)\b/.test(l)) return 'ok';
  return 'info';
}

async function loadLogs(id) {
  const box = $('#logs');
  try {
    const r = await api(`/devices/${id}/logs?lines=80`);
    const lines = (r.output || '').split('\n').map(s => s.replace(/\s+$/, '')).filter(Boolean);
    if (!box || view.deviceId !== id) return;
    $('#logs-src') && ($('#logs-src').textContent = r.ok ? `· ${lines.length} lignes` : '· indisponible');
    if (!lines.length) { box.innerHTML = `<div class="logline"><span class="logline__msg">${r.ok ? 'Aucune ligne de log.' : esc(r.error || 'Lecture impossible')}</span></div>`; return; }
    box.innerHTML = lines.map(line => {
      const lvl = detectLvl(line);
      return `<div class="logline"><span class="logline__lvl logline__lvl--${lvl}">${lvl.toUpperCase()}</span><span class="logline__msg">${esc(line)}</span></div>`;
    }).join('');
  } catch (e) {
    if (box && view.deviceId === id) box.innerHTML = `<div class="logline"><span class="logline__lvl logline__lvl--err">ERR</span><span class="logline__msg">${esc(e.message)}</span></div>`;
  }
}

/* ============================================================
   DATA LOADING + SSE
   ============================================================ */
async function loadFleet() {
  if (DEVICES.length === 0) view.status = 'loading';
  if (view.name === 'fleet') renderFleet();
  try {
    const list = await api('/devices');
    DEVICES = list.map(adapt);
    view.status = 'data';
    if (view.name === 'fleet') renderFleet();
  } catch (e) {
    view.status = 'error';
    if (view.name === 'fleet') renderFleet();
  }
}

/* quietly refresh the device list without disturbing focus/scroll */
async function silentRefresh() {
  try {
    const list = await api('/devices');
    DEVICES = list.map(adapt);
    if (view.name === 'fleet' && view.status === 'data') {
      const active = document.activeElement && document.activeElement.id === 'q';
      renderFleet();
      if (active) { const i = $('#q'); if (i) { i.focus(); const v = i.value; i.setSelectionRange(v.length, v.length); } }
    }
  } catch (e) { /* keep current data */ }
}

function connectStream() {
  const es = new EventSource('/api/stream');
  es.onmessage = (e) => {
    let ev; try { ev = JSON.parse(e.data); } catch (_) { return; }
    if (!ev || !ev.type) return;
    if (ev.type === 'device.state.changed' && ev.device) {
      const name = ev.device.name;
      const label = (STATE_META[STATE_KEY[ev.state]] || {}).label || ev.state;
      toast('État mis à jour', `${name} → ${label}`, 'info');
    }
    // toujours rafraîchir la vue courante
    silentRefresh();
    if (view.name === 'detail' && ev.deviceId === view.deviceId) {
      refreshDetailQuiet(view.deviceId);
    }
  };
  es.onerror = () => { /* EventSource reconnecte automatiquement */ };
}

async function refreshDetailQuiet(id) {
  try {
    const detail = await api(`/devices/${id}`);
    DETAIL = adapt(detail);
    if (view.name === 'detail' && view.deviceId === id) renderDetail();
  } catch (e) { /* ignore */ }
}

/* ============================================================
   BOOT
   ============================================================ */
loadFleet();
connectStream();
