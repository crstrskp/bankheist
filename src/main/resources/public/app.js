const $ = id => document.getElementById(id);
// sessionStorage survives reloads, but independent tabs can represent different players.
let credentials = JSON.parse(sessionStorage.getItem('heist-player') || 'null');
let state, revision = -1, connected = false;
const money = value => Number(value).toFixed(2);
async function api(path, {body, teacher = false, method = 'POST'} = {}) {
  const headers = {'Content-Type': 'application/json'};
  if (credentials) headers.Authorization = `Bearer ${credentials.reconnectToken}`;
  if (teacher) headers['X-Teacher-Key'] = $('teacher-key').value;
  const response = await fetch(path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body)});
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${response.status}: ${text}`);
  }
  return response.json();
}
async function action(work) {
  $('error').textContent = '';
  try { await work(); } catch (e) { $('error').textContent = e.message; }
}
function render(s) {
  state = s;
  const me = s.players.find(p => p.playerId === credentials?.playerId);
  $('join').hidden = !!credentials;
  $('identity').textContent = me ? `Playing as ${me.name}${me.participant ? '' : ' · waiting for the next match'}` : 'Join the crew, or watch as a spectator.';
  $('phase').textContent = s.phase;
  $('round').textContent = `Round ${s.roundNumber} / ${s.totalRounds}`;
  const escaped = me?.roundStatus === 'ESCAPED';
  const caught = me?.roundStatus === 'CAUGHT';
  const insidePlayers = s.players.filter(p => p.roundStatus === 'INSIDE');
  const personalAmount = !me?.participant ? null : escaped ? me.roundEarnings
    : caught ? s.result.bagValue : me.escapePayout;
  $('bag-label').textContent = !me?.participant ? 'Watching the crew' : escaped ? 'Safely banked'
    : caught ? 'Lost to the alarm' : 'Your bag';
  $('bag').replaceChildren(document.createTextNode(personalAmount === null ? '—' : `${money(personalAmount)} `),
    ...(personalAmount === null ? [] : [Object.assign(document.createElement('small'), {textContent:'gold'})]));
  $('bag-hint').textContent = !me?.participant ? 'Each player fills their own bag at 10 gold per second.'
    : caught ? 'Banked this round: 0.00 gold.' : escaped ? 'Your gold is safe.'
    : s.phase === 'ACTIVE' ? 'Escape now to bank it. Stay longer to earn more.' : 'Your bag grows at 10 gold per second.';
  let comparison = '';
  if (escaped && s.phase === 'ACTIVE' && insidePlayers.length) {
    comparison = `Players still inside can currently bank ${money(insidePlayers[0].escapePayout)} gold.`;
  } else if (escaped && s.result?.outcome === 'ALARM') {
    comparison = `You banked ${money(me.roundEarnings)} gold. Players who stayed were caught.`;
  } else if (escaped && s.result?.outcome === 'ALL_ESCAPED') {
    const lastAmount = Math.max(...s.result.earnings.map(e => e.earnings));
    const difference = lastAmount - me.roundEarnings;
    comparison = difference >= 0.005
      ? `The last player out banked ${money(lastAmount)} gold — ${money(difference)} more than you.`
      : `You banked ${money(me.roundEarnings)} gold — the largest bag this round.`;
  }
  $('comparison').textContent = comparison;
  $('time').textContent = s.phase === 'COUNTDOWN' ? `Doors open in ${Math.ceil(s.countdownSeconds)}…` : `Elapsed: ${s.elapsedSeconds.toFixed(1)} seconds`;
  $('escape').disabled = !connected || s.phase !== 'ACTIVE' || me?.roundStatus !== 'INSIDE';
  $('escape').textContent = me?.roundStatus === 'ESCAPED' ? 'YOU ARE SAFELY OUT'
    : me?.roundStatus === 'CAUGHT' ? 'CAUGHT BY THE ALARM' : 'ESCAPE & BANK MY BAG';
  $('match').disabled = !['LOBBY', 'FINISHED'].includes(s.phase);
  $('next').disabled = !['READY', 'RESULTS'].includes(s.phase);
  const name = id => s.players.find(p => p.playerId === id)?.name || id;
  const inside = s.players.filter(p => p.roundStatus === 'INSIDE').length;
  $('result').textContent = s.result ? s.result.outcome === 'ALARM'
    ? 'ALARM! Players still inside were caught. Escaped gold is safe.' : 'Everyone escaped safely. Round complete.'
    : s.phase === 'ACTIVE' ? `${me?.roundStatus === 'ESCAPED' ? 'Your gold is safe. ' : ''}${inside} player${inside === 1 ? '' : 's'} still inside.` : '';
  $('winners').textContent = s.winnerIds.length ? `Match winner${s.winnerIds.length > 1 ? 's' : ''}: ${s.winnerIds.map(name).join(', ')}` : '';
  $('players').replaceChildren(...[...s.players].sort((a,b) => b.score-a.score).map(p => {
    const tr = document.createElement('tr');
    const earned = ['ESCAPED', 'CAUGHT'].includes(p.roundStatus) ? p.roundEarnings : undefined;
    for (const text of [p.name + (p.participant ? '' : ' (waiting)'), p.roundStatus, earned === undefined ? '—' : money(earned), money(p.score)]) {
      const td = document.createElement('td'); td.textContent = text; tr.append(td);
    }
    return tr;
  }));
}
$('join-form').onsubmit = event => {
  event.preventDefault();
  action(async () => {
    credentials = await api('/players', {body:{name:$('name').value}});
    sessionStorage.setItem('heist-player', JSON.stringify(credentials));
    if (state) render(state);
  });
};
$('escape').onclick = () => action(async () => {
  $('escape').disabled = true;
  try { await api(`/rounds/${state.roundId}/escape`); } finally { if (state) render(state); }
});
$('match').onclick = () => action(() => api('/matches', {teacher:true}));
$('next').onclick = () => action(() => api(`/matches/${state.matchId}/rounds`, {teacher:true}));
$('deliveries').onclick = () => action(async () => {
  $('delivery-log').textContent = JSON.stringify(await api('/webhook-deliveries', {teacher:true, method:'GET'}), null, 2);
});
async function restoreIdentity() {
  if (!credentials) return;
  const response = await fetch('/players/me', {headers:{Authorization:`Bearer ${credentials.reconnectToken}`}});
  if (response.status === 401) {
    credentials = null; sessionStorage.removeItem('heist-player'); if (state) render(state);
  }
}
function connect() {
  const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/game`);
  let first = true;
  socket.onopen = () => { connected = true; $('connection').textContent = '● Live updates connected'; action(restoreIdentity); };
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    // A new connection may belong to a restarted server with revisions back at zero.
    if (first && message.type === 'STATE_SNAPSHOT') { revision = -1; first = false; }
    if (message.revision < revision) return;
    revision = message.revision; render(message.state);
  };
  socket.onclose = () => {
    connected = false; $('connection').textContent = 'Disconnected · reconnecting…';
    if (state) render(state);
    setTimeout(connect, 1000);
  };
  socket.onerror = () => socket.close();
}
connect();
