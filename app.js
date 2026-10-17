// Status keys, worst first; this is also the group order. `open`: whether the group starts expanded.
const STATUS = {
  fail: { label: 'Not working', open: true },
  maybe: { label: 'Might be broken', open: true },
  partial: { label: 'Partly working', open: true },
  untestable: { label: 'Can’t test', open: true },
  pending: { label: 'Not checked yet', open: false },
  pass: { label: 'Working', open: false },
  unsupported: { label: 'Not supported', open: false },
}
const ORDER = Object.keys(STATUS)
const BAR_ORDER = ['pass', 'partial', 'fail', 'maybe', 'untestable', 'unsupported', 'pending']
const LABEL = s => STATUS[s]?.label || s
// Why a site turned the checker away. Unknown tags still show, as their id.
const TAGS = {
  cloudflare: ['Cloudflare', 'Cloudflare blocked our checker'],
  http403: ['403', 'The site refused our checker (HTTP 403)'],
  ratelimit: ['Rate limited', 'The site rate limited our checker (HTTP 429)'],
  blocked: ['Blocked', 'The site refused our checker'],
}
const tagLabel = t => TAGS[t]?.[0] || t
const tagText = t => TAGS[t]?.[1] || `The site turned our checker away (${t})`
const $ = s => document.querySelector(s)
const el = (tag, attrs = {}, ...kids) => {
  const e = document.createElement(tag)
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v)
  e.append(...kids)
  return e
}
// Chrome rejects dateStyle/timeStyle together with timeZoneName, so spell the fields out.
const when = iso => new Intl.DateTimeFormat(undefined, {
  year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short',
}).format(new Date(iso))
const day = d => new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(d))
const shortDay = d => new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(d))
const num = n => n.toLocaleString()
const plural = (n, word) => `${num(n)} ${word}${n === 1 ? '' : 's'}`
const norm = s => s.toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, ' ').trim()

let langNames
try { langNames = new Intl.DisplayNames(undefined, { type: 'language' }) } catch { langNames = null }
const langName = code => {
  if (code === 'all') return 'Multi-language'
  try { return langNames?.of(code) || code } catch { return code }
}

// What each operation is called, and what a failure of it means, for people who don't care about the internals.
const OPS = {
  browse: ['Browse', 'Can’t load its list of titles'],
  latest: ['Latest updates', 'Latest updates don’t load'],
  search: ['Search', 'Search doesn’t work'],
  details: ['Open a title', 'Titles don’t open'],
  chapters: ['Chapter list', 'Chapter lists don’t load'],
  read: ['Open a chapter', 'Chapters don’t open'],
  pages: ['Load pages', 'Pages don’t load'],
}
const OP_STATUS = { pass: 'Works', fail: 'Fails', maybe: 'Turned away', skip: 'Not checked', untestable: 'Couldn’t check', na: 'Not applicable' }
const FROM_CODE = { p: 'pass', a: 'partial', f: 'fail', m: 'maybe', u: 'untestable', n: 'unsupported' }
const ISSUES = 'https://github.com/keiyoushi/extensions-source/issues'

let DATA = null

// Every run for one extension, oldest first, as [date, status|null]. The newest is this run unless it's still pending.
function runsOf(x) {
  const codes = DATA.history?.ext?.[x.id] || ''
  const runs = (DATA.history?.runs || []).map((d, i) => [d, FROM_CODE[codes[i]] || null])
  if (x.status === 'pending') return runs
  // Once this run has finished it's also in the history, so replace rather than add.
  if (runs.length && runs[runs.length - 1][0] === DATA.sweepDate) runs[runs.length - 1] = [DATA.sweepDate, x.status]
  else runs.push([DATA.sweepDate, x.status])
  return runs
}

function lastResult(x) {
  const r = runsOf(x).filter(([, st]) => st).pop()
  return r ? { status: r[1], date: r[0] } : null
}

// A node result only ever carries a fixed category (publisher/node_schema.py) — never free text —
// so this is the one place that turns that into something a reader sees. `error` is already the
// plain sentence merge_node_result.py wrote (natural phrasing, same style as OPS below); the only
// thing worked out here is the one case a node pass never needs to store a string for.
const nodeText = x => x.status === 'pass' ? 'Works from our recheck node' : (x.error || 'Also blocked from our recheck node')

// A listing whose first page works but the next doesn't: scrolling stops, the list itself still loads.
const failText = o => /^Page 2/.test(o.error || '') ? `${OPS[o.op]?.[0] || o.op} stops after page 1` : OPS[o.op]?.[1] || o.op

function plain(x) {
  if (x.status === 'pass') return 'Loads and reads fine'
  if (x.status === 'pending') {
    const last = lastResult(x)
    return last ? `Last result: ${LABEL(last.status)} (${shortDay(last.date)})` : 'Not checked before'
  }
  if (x.status === 'partial' || x.status === 'fail') {
    const failed = (x.ops || []).filter(o => o.status === 'fail').map(failText)
    if (failed.length) return failed[0] + (failed.length > 1 ? ` (+${failed.length - 1} more)` : '')
    return x.step ? (OPS[x.step]?.[1] || 'Something in it is broken') : 'Something in it is broken'
  }
  if (x.status === 'maybe') return x.tags?.length ? tagText(x.tags[0]) : 'The site turned our checker away'
  return x.error || 'Couldn’t check this one automatically'
}

function spark(x) {
  const runs = runsOf(x).slice(-14)
  return el('span', { class: 'spark', 'aria-hidden': 'true' },
    ...runs.map(([, st]) => el('span', { class: `sb ${st || 'none'}` })))
}

// Full history strip in the details; tap or hover a bar for its date and status.
function historyPanel(x) {
  const runs = runsOf(x)
  const checked = runs.filter(([, st]) => st && st !== 'untestable')
  const working = checked.filter(([, st]) => st === 'pass').length
  const readout = el('span', { class: 'readout' })
  const show = ([d, st]) => { readout.textContent = `${day(d)}: ${st ? LABEL(st) : 'not checked'}` }
  const bars = el('div', { class: 'bars', role: 'list' }, ...runs.map(r => {
    const label = `${day(r[0])}: ${r[1] ? LABEL(r[1]) : 'not checked'}`
    const b = el('span', { class: `hbar ${r[1] || 'none'}`, role: 'listitem', tabindex: '0', title: label, 'aria-label': label })
    for (const ev of ['mouseenter', 'focus', 'click']) b.addEventListener(ev, () => show(r))
    return b
  }))
  if (runs.length) show(runs[runs.length - 1])
  const summary = checked.length
    ? `Working in ${working} of ${checked.length} ${checked.length === 1 ? 'check' : 'checks'}`
    : 'Not checked yet'
  return el('div', { class: 'panel history' }, el('div', { class: 'phead' }, el('span', {}, 'History'), el('span', { class: 'muted' }, summary)), bars, readout)
}

const dotClass = st => ['skip', 'na'].includes(st) ? 'skip' : st

// Where in the read path it broke: one chip per operation, in the order the app does them.
function stepper(x) {
  const chips = x.ops.flatMap((o, i) => [
    ...(i ? [el('span', { class: 'sep', 'aria-hidden': 'true' }, '›')] : []),
    el('span', { class: `step ${o.status}`, title: OP_STATUS[o.status] || o.status },
      el('span', { class: `dot ${dotClass(o.status)}` }), OPS[o.op]?.[0] || o.op,
      el('span', { class: 'vh' }, `: ${OP_STATUS[o.status] || o.status}`)),
  ])
  return el('div', { class: 'stepper' }, ...chips)
}

function copyText(text) {
  return navigator.clipboard?.writeText(text).catch(fallback) ?? Promise.resolve(fallback())
  function fallback() {
    const t = el('textarea', {}, text)
    document.body.append(t)
    t.select()
    document.execCommand('copy')
    t.remove()
  }
}

function copyButton(label, text, cls = 'btn') {
  const b = el('button', { type: 'button', class: cls }, label)
  b.addEventListener('click', async e => {
    e.preventDefault()
    await copyText(typeof text === 'function' ? text() : text)
    b.textContent = 'Copied'
    b.classList.add('done')
    setTimeout(() => { b.textContent = label; b.classList.remove('done') }, 1500)
  })
  return b
}

const linkTo = x => `${location.origin}${location.pathname}#${x.id}`
const version = x => `${x.id} v${x.libVersion || '1.6'}.${x.versionCode}`

function report(x) {
  const lines = [`${x.name} (${version(x)}): ${LABEL(x.status)}`]
  if (x.recheckedOnNode) {
    lines.push(nodeText(x))
  } else {
    for (const o of x.ops || []) if (['fail', 'maybe'].includes(o.status)) lines.push(`${OPS[o.op]?.[0] || o.op}: ${o.error}`)
    if (!x.ops?.some(o => ['fail', 'maybe'].includes(o.status)) && x.error) lines.push(x.error)
  }
  lines.push(`Checked ${x.checked ? when(x.checked) : DATA.sweepDate} on main @ ${(DATA.keiCommit || '').slice(0, 10)}`, linkTo(x))
  return lines.join('\n')
}

function details(x) {
  const parts = []
  if (x.status === 'pending') {
    parts.push(el('p', { class: 'panel muted' }, 'Today’s run hasn’t reached this one yet. ', plain(x), '.'))
  }
  if (x.status === 'maybe') {
    parts.push(el('p', { class: 'panel muted' }, `${plain(x)}, so we can’t tell if the extension works. `,
      'Could be the site blocking automated checks, could be the extension. Check in the app.'))
  }
  if (x.recheckedOnNode) {
    // A node's result is never a GitHub-side step failure — it's one fixed category, reported
    // separately from (and not a substitute for) whatever GitHub's own servers saw. The GitHub
    // ops below are real data from this run, just not what the node checked, so they're labelled
    // rather than hidden.
    parts.push(el('p', { class: 'panel muted' }, el('b', {}, 'Rechecked from a node: '), nodeText(x),
      x.recheckedOn ? ` (${day(x.recheckedOn)})` : ''))
  }
  if (x.ops?.length) {
    if (x.recheckedOnNode) parts.push(el('p', { class: 'muted small' }, 'From GitHub’s servers:'))
    parts.push(stepper(x))
    // Steps skipped because an earlier one failed only repeat that failure, so they're left out.
    const failed = x.ops.some(o => ['fail', 'maybe'].includes(o.status))
    const notes = x.ops.filter(o => (o.error || o.reason) && !(failed && o.status === 'skip'))
    if (notes.length) {
      parts.push(el('div', { class: 'panel notes' }, ...notes.map(o => el('div', { class: `nitem ${o.status}` },
        el('div', { class: 'nhead' }, el('span', { class: `dot ${dotClass(o.status)}` }), el('b', {}, OPS[o.op]?.[0] || o.op),
          el('span', { class: `opstatus ${o.status}` }, OP_STATUS[o.status] || o.status)),
        ['fail', 'maybe'].includes(o.status) ? el('pre', { class: 'err' }, o.error) : el('p', { class: 'muted' }, o.error || o.reason)))))
    }
  } else if (x.error && !x.recheckedOnNode) {
    parts.push(el('p', { class: 'panel' }, x.error))
  }
  if (x.notes?.length) {
    parts.push(el('div', { class: 'panel notes' }, el('div', { class: 'nhead' }, el('b', {}, 'Notes')),
      el('ul', { class: 'nlist' }, ...x.notes.map(n => el('li', {}, n)))))
  }
  if (x.status !== 'unsupported' && DATA.history) parts.push(historyPanel(x))

  const rows = [['Extension', version(x)], ['Language', `${langName(x.lang)} (${x.lang})`]]
  if (x.checkedWith) rows.push(['Checked with', x.checkedWith])
  if (x.checked) rows.push(['Checked', when(x.checked)])
  if (x.since && x.status !== 'pending') rows.push([`${LABEL(x.status)} since`, day(x.since)])
  parts.push(el('dl', { class: 'panel' }, ...rows.flatMap(([k, v]) => [el('dt', {}, k), el('dd', {}, v)])))

  const issues = el('a', { class: 'btn', href: `${ISSUES}?q=${encodeURIComponent(`is:issue ${x.name}`)}`, target: '_blank', rel: 'noopener' },
    'Search issues ↗')
  parts.push(el('div', { class: 'actions' }, copyButton('Copy link', () => linkTo(x)),
    ...(['fail', 'partial', 'maybe', 'untestable'].includes(x.status) ? [copyButton('Copy report', () => report(x))] : []), issues))
  return el('div', { class: 'detail' }, ...parts)
}

// "Since" only means something once the status has changed after the first run we have.
const changedSince = x => x.since && !['pending', 'unsupported'].includes(x.status) && x.since > (DATA.history?.runs?.[0] || DATA.sweepDate)

function makeRow(x) {
  const nsfw = x.contentWarning === 'NSFW'
  // A node-rechecked pass is still worth a line (so it doesn't look identical to a GitHub-side
  // pass that was never flaky) — the rest of a plain pass stays silent, as before.
  const issue = (x.status === 'pass' && !x.recheckedOnNode) ? null
    : el('span', { class: `issue ${x.status}` }, x.recheckedOnNode ? nodeText(x) : plain(x),
      ...(changedSince(x) ? [el('span', { class: 'since' }, ` · since ${shortDay(x.since)}`)] : []))
  const det = el('details', {},
    el('summary', { class: 'row' },
      el('span', { class: `dot ${x.status}`, title: LABEL(x.status) }),
      el('span', { class: 'main' },
        el('span', { class: 'top' }, el('span', { class: 'name' }, x.name),
          el('span', { class: 'tag', title: langName(x.lang) }, x.lang),
          ...(nsfw ? [el('span', { class: 'tag nsfw' }, '18+')] : []),
          ...(x.tags || []).map(t => el('span', { class: 'tag block', title: tagText(t) }, tagLabel(t))),
          ...(x.notes?.length ? [el('span', { class: 'tag note', title: x.notes.join('\n') }, x.notes.length === 1 ? '1 note' : `${x.notes.length} notes`)] : []),
          el('span', { class: 'vh' }, `, ${LABEL(x.status)}`)),
        ...(issue ? [issue] : [])),
      x.status === 'unsupported' ? el('span') : spark(x),
      el('span', { class: 'chev', 'aria-hidden': 'true' })))
  det.addEventListener('toggle', () => { if (det.open && !det.dataset.built) { det.append(details(x)); det.dataset.built = '1' } })
  return el('li', { id: x.id, class: `item ${x.status}` }, det)
}

function showChanges(state, pool) {
  const byId = new Map(DATA.extensions.map(x => [x.id, x]))
  const ok = new Set(pool.map(r => r.x.id))
  const { newlyFailing = [], nowPassing = [] } = DATA.changes || {}
  const items = [...newlyFailing.map(id => [id, false]), ...nowPassing.map(id => [id, true])]
    .filter(([id]) => ok.has(id) && byId.has(id))
  $('#changes').hidden = !items.length
  if (!items.length) return
  if (DATA.previousSweepDate) $('#changes-title').textContent = `Changed since ${shortDay(DATA.previousSweepDate)}`
  const limit = state.allChanges ? items.length : 6
  $('#clist').replaceChildren(...items.slice(0, limit).map(([id, fixed]) => {
    const x = byId.get(id)
    return el('li', {},
      el('span', { class: `dot ${fixed ? 'pass' : x.status}` }),
      el('a', { href: `#${id}` }, x.name), el('span', { class: 'tag', title: langName(x.lang) }, x.lang),
      el('span', { class: `issue ${fixed ? 'pass' : x.status}` }, fixed ? 'Working again' : plain(x)))
  }))
  const more = $('#cmore')
  more.hidden = items.length <= 6
  more.textContent = state.allChanges ? 'Show fewer' : `Show all ${items.length}`
}

function render(data) {
  DATA = data
  const exts = data.extensions
  const run = data.inProgress
  $('#meta').textContent = `${plural(exts.length, 'extension')} · checked daily · main @ ${(data.keiCommit || '').slice(0, 10)} · ` +
    (run ? `updated ${when(data.generated)}` : `last run ${when(data.generated)}`)
  if (run) {
    $('#progress').hidden = false
    $('#progress-text').textContent = `Today’s run: ${num(run.done)} of ${num(run.total)} checked`
    $('#pfill').style.width = `${Math.min(100, (100 * run.done) / run.total)}%`
  }

  const langs = [...new Set(exts.map(x => x.lang))].sort((a, b) => a === 'all' ? -1 : b === 'all' ? 1 : langName(a).localeCompare(langName(b)))
  for (const l of langs) $('#lang').append(el('option', { value: l }, langName(l)))

  const rows = exts.map(x => ({
    x, li: makeRow(x), nsfw: x.contentWarning === 'NSFW',
    name: x.name.toLowerCase(),
    text: norm(`${x.name} ${x.id} ${x.lang} ${langName(x.lang)} ${plain(x)} ${x.error || ''} ${(x.ops || []).map(o => o.error || '').join(' ')} ${(x.notes || []).join(' ')} ${(x.tags || []).map(tagLabel).join(' ')}`),
  }))
  const rowById = new Map(rows.map(r => [r.x.id, r]))

  const params = new URLSearchParams(location.search)
  const state = {
    q: params.get('q') || '', status: STATUS[params.get('status')] ? params.get('status') : '', lang: params.get('lang') || '',
    tag: params.get('tag') || '',
    content: ['all', 'nsfw'].includes(params.get('content')) ? params.get('content') : '',
    sort: ['name', 'recent'].includes(params.get('sort')) ? params.get('sort') : '',
    allChanges: false, groupOpen: {},
  }
  $('#q').value = state.q
  $('#lang').value = state.lang
  $('#content').value = state.content
  // Reasons are whatever tags this run produced, so a new one needs no page change.
  const tags = [...new Set(exts.flatMap(x => x.tags || []))].sort((a, b) => tagLabel(a).localeCompare(tagLabel(b)))
  for (const t of tags) $('#tag').append(el('option', { value: t }, tagLabel(t)))
  $('#tag').hidden = !tags.length
  if (!tags.includes(state.tag)) state.tag = ''
  $('#tag').value = state.tag
  $('#sort').value = state.sort

  // Legend buttons are the status filter; the stacked bar above mirrors them and is clickable too.
  const setStatus = s => { state.status = state.status === s ? '' : s; apply() }
  const legend = BAR_ORDER.map(s => {
    const b = el('button', { type: 'button', 'data-status': s, 'aria-pressed': 'false' },
      el('span', { class: `dot ${s}` }), LABEL(s), el('span', { class: 'n' }, ''))
    b.addEventListener('click', () => setStatus(s))
    $('#legend').append(b)
    return b
  })
  const segs = BAR_ORDER.map(s => {
    const seg = el('span', { class: `seg ${s}` })
    seg.addEventListener('click', () => setStatus(s))
    $('#sbar').append(seg)
    return seg
  })
  const headline = el('p', { class: 'headline' })
  $('.summary').prepend(headline)
  // The summary is the whole repo and stays put while you search or filter; the group headers carry the filtered counts.
  const counts = Object.fromEntries(BAR_ORDER.map(s => [s, exts.filter(x => x.status === s).length]))
  const checked = counts.pass + counts.partial + counts.fail
  headline.replaceChildren(checked
    ? el('span', {}, el('b', {}, `${Math.round((100 * counts.pass) / checked)}%`), ` of the ${num(checked)} we could check are working`)
    : el('span', { class: 'muted' }, 'Nothing checked yet'))
  BAR_ORDER.forEach((s, i) => {
    segs[i].style.flexGrow = counts[s]
    segs[i].hidden = !counts[s]
    legend[i].querySelector('.n').textContent = num(counts[s])
    legend[i].hidden = !counts[s]
  })
  // "How it's checked" lives collapsed in the sidebar; links to it open it.
  const openAbout = () => { $('#about').open = true; $('#about').scrollIntoView({ block: 'nearest' }) }
  for (const a of document.querySelectorAll('a[href="#about"]')) a.addEventListener('click', e => { e.preventDefault(); openAbout() })

  // One section per status, for the grouped view, plus a flat list for the other sorts.
  const groups = Object.fromEntries(ORDER.map(s => {
    const head = el('button', { type: 'button', class: 'ghead', 'aria-expanded': 'false' },
      el('span', { class: `dot ${s}` }), el('span', { class: 'glabel' }, LABEL(s)), el('span', { class: 'n' }, ''), el('span', { class: 'chev' }))
    const list = el('ul', { class: 'list' })
    const sec = el('section', { class: `group ${s}` }, head, list)
    head.addEventListener('click', () => { state.groupOpen[s] = head.getAttribute('aria-expanded') !== 'true'; apply() })
    return [s, { sec, head, list }]
  }))
  const flat = el('ul', { class: 'list flat' })
  $('#groups').append(...ORDER.map(s => groups[s].sec), flat)

  const contentOk = r => state.content === 'all' || (state.content === 'nsfw' ? r.nsfw : !r.nsfw)
  const langOk = r => (!state.lang || r.x.lang === state.lang) && (!state.tag || (r.x.tags || []).includes(state.tag))
  const statusOk = r => !state.status || r.x.status === state.status
  const byName = (a, b) => a.name.localeCompare(b.name)
  const recent = (a, b) => (b.x.status !== 'pending' && b.x.since || '').localeCompare(a.x.status !== 'pending' && a.x.since || '') ||
    ORDER.indexOf(a.x.status) - ORDER.indexOf(b.x.status) || byName(a, b)

  function apply() {
    const words = norm(state.q).split(' ').filter(Boolean)
    const searchOk = r => words.every(w => r.text.includes(w))
    const pool = rows.filter(r => contentOk(r) && langOk(r) && searchOk(r))  // everything but the status filter
    const shown = pool.filter(statusOk)

    BAR_ORDER.forEach((s, i) => {
      segs[i].classList.toggle('dim', !!state.status && state.status !== s)
      legend[i].setAttribute('aria-pressed', String(state.status === s))
    })
    showChanges(state, pool)

    if (state.sort) {
      for (const g of Object.values(groups)) g.sec.hidden = true
      flat.hidden = false
      flat.replaceChildren(...shown.sort(state.sort === 'name' ? byName : recent).map(r => r.li))
    } else {
      flat.hidden = true
      flat.replaceChildren()
      for (const s of ORDER) {
        const g = groups[s]
        const members = shown.filter(r => r.x.status === s).sort(byName)
        const open = state.groupOpen[s] ?? (words.length || state.status ? true : STATUS[s].open)
        g.sec.hidden = !members.length
        g.head.setAttribute('aria-expanded', String(open))
        g.head.querySelector('.n').textContent = num(members.length)
        g.list.replaceChildren(...(open ? members.map(r => r.li) : []))
      }
    }

    const total = rows.filter(r => contentOk(r) && langOk(r)).length
    $('#count').textContent = shown.length === total ? plural(total, 'extension') : `${num(shown.length)} of ${plural(total, 'extension')}`
    // A search that matches things the other filters hide says so, with a way to show them.
    const hidden = words.length ? rows.filter(r => searchOk(r) && !(contentOk(r) && langOk(r) && statusOk(r))).length : 0
    const hint = $('#hidden-hint')
    hint.hidden = hidden === 0
    if (hidden) {
      const show = el('a', { href: '#' }, 'Show them')
      show.addEventListener('click', e => {
        e.preventDefault()
        Object.assign(state, { status: '', lang: '', tag: '', content: 'all' })
        $('#lang').value = ''; $('#tag').value = ''; $('#content').value = 'all'
        apply()
      })
      hint.replaceChildren(`${num(hidden)} more match “${state.q.trim()}” but your filters hide ${hidden === 1 ? 'it' : 'them'}. `, show)
    }
    const empty = $('#empty')
    empty.hidden = shown.length > 0 || hidden > 0
    empty.textContent = words.length ? `No extension matches “${state.q.trim()}”.` : 'Nothing matches that.'
    const p = new URLSearchParams(['q', 'status', 'lang', 'tag', 'content', 'sort'].filter(k => state[k]).map(k => [k, state[k]]))
    history.replaceState(null, '', (p.size ? `?${p}` : location.pathname) + location.hash)
  }

  // A linked extension is always shown: clear whatever filter would hide it, open its group and its details.
  function showHash() {
    document.querySelectorAll('li.linked').forEach(l => l.classList.remove('linked'))
    const r = rowById.get(decodeURIComponent(location.hash.slice(1)))
    if (!r) return
    if (!contentOk(r)) state.content = 'all'
    if (!langOk(r)) { state.lang = ''; state.tag = '' }
    if (!statusOk(r)) state.status = ''
    if (!norm(state.q).split(' ').filter(Boolean).every(w => r.text.includes(w))) state.q = ''
    state.groupOpen[r.x.status] = true
    $('#q').value = state.q; $('#lang').value = state.lang; $('#tag').value = state.tag; $('#content').value = state.content
    apply()
    const det = r.li.querySelector('details')
    det.open = true
    r.li.classList.remove('flash')
    void r.li.offsetWidth // restart the animation
    r.li.classList.add('linked', 'flash')
    r.li.scrollIntoView({ block: 'center' })
  }

  $('#cmore').addEventListener('click', () => { state.allChanges = !state.allChanges; apply() })
  $('#q').addEventListener('input', e => { state.q = e.target.value; apply() })
  $('#lang').addEventListener('change', e => { state.lang = e.target.value; apply() })
  $('#tag').addEventListener('change', e => { state.tag = e.target.value; apply() })
  $('#content').addEventListener('change', e => { state.content = e.target.value; apply() })
  $('#sort').addEventListener('change', e => { state.sort = e.target.value; apply() })
  document.addEventListener('keydown', e => {
    const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement?.tagName)
    if (e.key === '/' && !typing) { e.preventDefault(); $('#q').focus() }
    if (e.key === 'Escape' && document.activeElement === $('#q')) { state.q = ''; $('#q').value = ''; apply() }
  })
  apply()
  if (location.hash === '#about') openAbout()
  if (location.hash.length > 1 && rowById.has(decodeURIComponent(location.hash.slice(1)))) showHash()
  window.addEventListener('hashchange', showHash)
}

fetch('status.json', { cache: 'no-cache' })
  .then(r => r.json())
  .catch(() => null)
  .then(data => {
    if (!data) { $('#meta').textContent = 'Couldn’t load the results, try a refresh.'; return }
    render(data)
  })
