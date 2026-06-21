/**
 * 动态建表入库平台 v3 — 多库路由 + 定时任务
 */
const $ = s => document.querySelector(s);
const $$ = s => document.querySelectorAll(s);

// ========== 面板切换 ==========
let activePanel = 'ingest';
$$('.nav-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    $$('.nav-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    activePanel = btn.dataset.panel;
    $$('.panel-group').forEach(p => p.classList.add('hidden'));
    document.getElementById('panel-' + activePanel).classList.remove('hidden');
    if (activePanel === 'connections') loadConnections();
    if (activePanel === 'tasks')       { loadConnSelects(); loadTasks(); }
  });
});

// ========== Utils ==========
const API_BASE = window.location.port === '8765' ? '' : 'http://localhost:8765';

async function api(path, opts = {}) {
  const h = opts.body instanceof FormData ? {} : { 'Content-Type': 'application/json' };
  const res = await fetch(API_BASE + path, { headers: h, ...opts });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }
  const text = await res.text();
  try { return JSON.parse(text); }
  catch (e) { throw new Error(text || `HTTP ${res.status}`); }
}

function esc(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

// ========== Connection Selects ==========
async function loadConnSelects() {
  try {
    const conns = await api('/api/connections');
    const opts = '<option value="">默认库 (H2)</option>' +
      conns.map(c => `<option value="${c.ID}">${c.NAME} (${c.TYPE})</option>`).join('');
    $('#connSelect').innerHTML = opts;
    $('#taskConnSelect').innerHTML = opts;
  } catch(e) { console.error(e); }
}

// ========== Panel: 数据入库 ==========
const tableNameEl=$('#tableName'), jsonInputEl=$('#jsonInput');
const btnIngest=$('#btnIngest'), ingestResult=$('#ingestResult');
const ingestMode=$('#ingestMode'), keyColumnEl=$('#keyColumn'), connSelect=$('#connSelect');
const btnUpload=$('#btnUploadWord'), uploadResult=$('#uploadResult');
const wordFileEl=$('#wordFile'), uploadHint=$('.upload-hint'), uploadInfo=$('#uploadInfo');
const uploadFileName=$('#uploadFileName');
const tableListEl=$('#tableList'), detailArea=$('#detailArea');
const btnRefresh=$('#btnRefreshTables'), btnClearDb=$('#btnClearDb');
let currentTable = null;

document.addEventListener('DOMContentLoaded', () => {
  loadConnSelects();
  loadTables();
  btnIngest.addEventListener('click', doIngest);
  btnRefresh.addEventListener('click', loadTables);
  btnClearDb.addEventListener('click', clearDb);
  btnUpload.addEventListener('click', doUploadWord);
  ingestMode.addEventListener('change', () => {
    keyColumnEl.style.display = ingestMode.value === 'upsert' ? '' : 'none';
  });
  wordFileEl.addEventListener('change', onFileSelected);
  const area = $('#uploadArea');
  if (area) {
    area.addEventListener('dragover', e => { e.preventDefault(); area.classList.add('drag-over'); });
    area.addEventListener('dragleave', () => area.classList.remove('drag-over'));
    area.addEventListener('drop', e => {
      e.preventDefault(); area.classList.remove('drag-over');
      if (e.dataTransfer.files.length) { wordFileEl.files = e.dataTransfer.files; onFileSelected(); }
    });
  }

  // 连接切换 → 刷新表列表
  connSelect.addEventListener('change', () => { loadTables(); $('#connLabel').textContent = connSelect.value ? `(${connSelect.selectedOptions[0].text})` : ''; });

  jsonInputEl.value = JSON.stringify([
    {"name":"张三","age":28,"email":"zs@test.com","active":true},
    {"name":"李四","age":35,"email":"lisi@test.com","active":false}
  ], null, 2);
});

function onFileSelected() {
  const f = wordFileEl.files[0];
  if (f) {
    uploadFileName.textContent = `📎 ${f.name} (${f.size<1024?f.size+'B':(f.size/1024).toFixed(1)+'KB'})`;
    uploadHint.classList.add('hidden'); uploadInfo.classList.remove('hidden');
  }
}

async function doUploadWord() {
  const f = wordFileEl.files[0];
  if (!f) return showBox(uploadResult, '请先选择文件', 'error');
  if (!f.name.endsWith('.docx')) return showBox(uploadResult, '仅支持 .docx', 'error');
  btnUpload.disabled = true; btnUpload.textContent = '解析中...';
  const form = new FormData(); form.append('file', f);
  const m = ingestMode.value; if (m) form.append('mode', m);
  const k = keyColumnEl.value.trim(); if (k) form.append('keyColumn', k);
  const c = connSelect.value; if (c) form.append('connectionId', c);
  try {
    const r = await api('/api/upload/word', { method:'POST', body:form });
    let msg = `✅ ${r.sourceFile}\n${r.tablesParsed} 个表, ${r.totalRowsInserted} 行\n`;
    for (const t of (r.details||[])) msg += `📊 ${t.table}: ${t.rowsInserted||0} 行${t.error?' ❌ '+t.error:''}\n`;
    showBox(uploadResult, msg, 'success'); loadTables();
  } catch(e) { showBox(uploadResult, '❌ '+e.message, 'error'); }
  finally { btnUpload.disabled = false; btnUpload.textContent = '解析并入库'; }
}

async function doIngest() {
  const tn = tableNameEl.value.trim(), jt = jsonInputEl.value.trim();
  const mode = ingestMode.value, kc = keyColumnEl.value.trim(), ci = connSelect.value;
  if (!tn) return showBox(ingestResult,'请输入表名','error');
  if (!jt) return showBox(ingestResult,'请输入 JSON','error');
  if (mode==='upsert' && !kc) return showBox(ingestResult,'upsert 需要 keyColumn','error');
  try { JSON.parse(jt); } catch(e) { return showBox(ingestResult,'JSON 格式错误: '+e.message,'error'); }
  btnIngest.disabled = true; btnIngest.textContent = '提交中...';
  try {
    const body = { table:tn, data:jt, mode, connectionId: ci||undefined };
    if (kc) body.keyColumn = kc;
    const r = await api('/api/ingest', { method:'POST', body:JSON.stringify(body) });
    showBox(ingestResult, `✅ 入库成功！\n模式: ${r.mode}\n表: ${r.table}\n入库: ${r.rowsInserted} 行\n连接: ${r.connectionId||'default'}\n列: ${Object.keys(r.columns||{}).join(', ')}`, 'success');
    loadTables();
  } catch(e) { showBox(ingestResult,'❌ '+e.message,'error'); }
  finally { btnIngest.disabled = false; btnIngest.textContent = '提交入库'; }
}

function showBox(el, msg, type) { el.textContent = msg; el.className = 'result-box '+type; el.classList.remove('hidden'); }

async function loadTables() {
  try {
    const ci = connSelect.value;
    const tables = await api('/api/tables' + (ci ? '?connectionId='+ci : ''));
    tableListEl.innerHTML = tables.length ? tables.map(t =>
      `<li onclick="selectTable('${t.TABLE_NAME}')"><span>📊 ${t.TABLE_NAME}</span><span class="badge">${t.TABLE_TYPE}</span></li>`
    ).join('') : '<li style="color:var(--text-dim);cursor:default;">暂无表</li>';
  } catch(e) { console.error(e); }
}

async function selectTable(name) {
  currentTable = name;
  $$('.table-list li').forEach(l => l.classList.remove('active'));
  document.querySelector(`.table-list li[onclick*="${name}"]`)?.classList.add('active');
  try {
    const ci = connSelect.value, q = ci ? '?connectionId='+ci : '';
    const [desc, data] = await Promise.all([
      api(`/api/tables/${encodeURIComponent(name)}/describe${q}`),
      api(`/api/tables/${encodeURIComponent(name)}/data${q}`),
    ]);
    const cols = desc.columns || [];
    detailArea.innerHTML = `
      <div style="display:flex;justify-content:space-between;align-items:center;">
        <h2 style="margin:0;">📊 ${name}</h2><span style="color:var(--text-dim);font-size:0.82rem;">${desc.rowCount} 行 · ${cols.length} 列</span>
      </div>
      <div class="tab-bar">
        <button class="tab-btn active" onclick="$('#tab-schema').style.display='block';$('#tab-data').style.display='none';$$('.tab-btn')[0].classList.add('active');$$('.tab-btn')[1].classList.remove('active')">📐 表结构</button>
        <button class="tab-btn" onclick="$('#tab-schema').style.display='none';$('#tab-data').style.display='block';$$('.tab-btn')[1].classList.add('active');$$('.tab-btn')[0].classList.remove('active')">📋 数据预览</button>
      </div>
      <div id="tab-schema"><table class="schema-table"><thead><tr><th>列名</th><th>类型</th><th>可空</th><th>键</th></tr></thead><tbody>${cols.map(c=>`<tr><td><strong>${c.FIELD}</strong></td><td><code>${c.TYPE}</code></td><td>${c.NULL}</td><td>${c.KEY||''}</td></tr>`).join('')}</tbody></table></div>
      <div id="tab-data" style="display:none;"><div class="data-table-wrap"><table class="data-table"><thead><tr>${(data.rows||[]).length?Object.keys(data.rows[0]).map(k=>`<th>${k}</th>`).join(''):'<th>无数据</th>'}</tr></thead><tbody>${(data.rows||[]).map(r=>'<tr>'+Object.values(r).map(v=>`<td>${v===null?'<em style="color:var(--text-dim)">NULL</em>':esc(String(v))}</td>`).join('')+'</tr>').join('')}</tbody></table></div></div>`;
  } catch(e) { detailArea.innerHTML = `<div class="card">❌ ${e.message}</div>`; }
}

async function clearDb() {
  if (!confirm('确定清空当前库所有表？')) return;
  try {
    const ci = connSelect.value, q = ci ? '?connectionId='+ci : '';
    const tables = await api('/api/tables'+q);
    for (const t of tables) {
      try { await fetch(API_BASE+`/api/tables/${encodeURIComponent(t.TABLE_NAME)}/drop${q}`, {method:'DELETE'}); } catch(e){}
    }
    loadTables(); detailArea.innerHTML = '<div class="card placeholder">点击左侧表名查看结构和数据</div>';
  } catch(e) { alert('清空失败: '+e.message); }
}

// ========== Panel: 连接管理 ==========
async function loadConnections() {
  try {
    const conns = await api('/api/connections');
    const tbody = document.querySelector('#connTable tbody');
    tbody.innerHTML = conns.length ? conns.map(c =>
      `<tr>
        <td><strong>${c.NAME}</strong></td><td><code>${c.TYPE}</code></td>
        <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;">${esc(c.URL)}</td>
        <td>${c.USERNAME||'-'}</td>
        <td><button class="btn btn-danger btn-xs" onclick="delConn('${c.ID}')">删除</button></td>
      </tr>`
    ).join('') : '<tr><td colspan="5" style="color:var(--text-dim);text-align:center;">暂无连接</td></tr>';
  } catch(e) { console.error(e); }
}

async function addConn() {
  const name=$('#connName').value.trim(), type=$('#connType').value;
  const url=$('#connUrl').value.trim(), user=$('#connUser').value.trim();
  const pass=$('#connPass').value.trim();
  if (!name||!url) return showBox($('#connResult'),'名称和 URL 必填','error');
  $('#btnAddConn').disabled = true;
  try {
    const r = await api('/api/connections', { method:'POST', body:JSON.stringify({name,type,url,username:user,password:pass}) });
    showBox($('#connResult'), `✅ 连接已添加: ${r.id}`, 'success');
    $('#connName').value='';$('#connUrl').value='';$('#connUser').value='';$('#connPass').value='';
    loadConnections(); loadConnSelects();
  } catch(e) { showBox($('#connResult'), '❌ '+e.message, 'error'); }
  finally { $('#btnAddConn').disabled = false; }
}

async function testConn() {
  const type=$('#connType').value, url=$('#connUrl').value.trim();
  const user=$('#connUser').value.trim(), pass=$('#connPass').value.trim();
  if (!url) return showBox($('#connResult'),'URL 必填','error');
  $('#btnTestConn').disabled = true; $('#btnTestConn').textContent = '测试中...';
  try {
    const r = await api('/api/connections/test', { method:'POST', body:JSON.stringify({type,url,username:user,password:pass}) });
    showBox($('#connResult'), r.success ? `✅ 连接成功! ${r.dbProduct} (${r.latencyMs}ms)` : `❌ ${r.error}`, r.success?'success':'error');
  } catch(e) { showBox($('#connResult'), '❌ '+e.message, 'error'); }
  finally { $('#btnTestConn').disabled = false; $('#btnTestConn').textContent = '测试连接'; }
}

async function delConn(id) {
  if (!confirm('确定删除此连接？')) return;
  try { await fetch(API_BASE+`/api/connections/${id}`, {method:'DELETE'}); loadConnections(); loadConnSelects(); }
  catch(e) { alert('删除失败: '+e.message); }
}

// URL 模板
const urlTemplates = {
  H2:          'jdbc:h2:file:./data/mydb',
  MySQL:       'jdbc:mysql://localhost:3306/mydb?useSSL=false&serverTimezone=Asia/Shanghai',
  MariaDB:     'jdbc:mariadb://localhost:3306/mydb',
  PostgreSQL:  'jdbc:postgresql://localhost:5432/mydb',
  SQLServer:   'jdbc:sqlserver://localhost:1433;database=mydb;encrypt=false',
  Oracle:      'jdbc:oracle:thin:@localhost:1521/XEPDB1',
  SQLite:      'jdbc:sqlite:./data/mydb.db',
};

$('#connType')?.addEventListener('change', function() {
  const tpl = urlTemplates[this.value];
  if (tpl) $('#connUrl').value = tpl;
  // 默认用户名
  if (this.value === 'H2' || this.value === 'SQLite') { $('#connUser').value = 'sa'; $('#connPass').value = ''; }
  else if (this.value === 'PostgreSQL') $('#connUser').value = 'postgres';
  else if (this.value === 'Oracle') $('#connUser').value = 'system';
  else $('#connUser').value = 'root';
});

document.getElementById('btnAddConn')?.addEventListener('click', addConn);
document.getElementById('btnTestConn')?.addEventListener('click', testConn);

// ========== Panel: 定时任务 ==========
$('#taskFreq')?.addEventListener('change', function() {
  $('#taskCronCustom').style.display = this.value === 'custom' ? '' : 'none';
});

async function loadTasks() {
  try {
    const tasks = await api('/api/tasks');
    const el = $('#taskList');
    el.innerHTML = tasks.length ? tasks.map(t => `
      <div class="task-card">
        <div class="task-info">
          <strong>${esc(t.NAME)}</strong>
          <div class="task-meta">
            📊 ${esc(t.TABLE_NAME)} · ⏰ ${esc(t.CRON_DESC||t.CRON_EXPR)}
            ${t.LAST_RUN ? ' · 上次: '+t.LAST_RUN : ''}
          </div>
        </div>
        <div class="task-actions">
          <span class="status-dot ${t.ENABLED?'on':'off'}" title="${t.ENABLED?'运行中':'已停用'}"></span>
          <button class="btn btn-outline btn-xs" onclick="toggleTask('${t.ID}',${!t.ENABLED})">${t.ENABLED?'停用':'启用'}</button>
          <button class="btn btn-outline btn-xs" onclick="triggerTask('${t.ID}')">▶ 执行</button>
          <button class="btn btn-danger btn-xs" onclick="delTask('${t.ID}')">删除</button>
        </div>
      </div>
    `).join('') : '<div style="color:var(--text-dim);text-align:center;padding:20px;">暂无定时任务</div>';
  } catch(e) { console.error(e); }
}

async function addTask() {
  const name = $('#taskName').value.trim();
  const tableName = $('#taskTable').value.trim();
  const ci = $('#taskConnSelect').value;
  const url = $('#taskUrl').value.trim();
  const jsonBody = $('#taskJsonBody').value.trim();
  const freq = $('#taskFreq').value;
  const cronExpr = freq === 'custom' ? $('#taskCronCustom').value.trim() : freq;
  const cronDesc = $('#taskFreq').selectedOptions[0]?.text || '';

  if (!name || !tableName || !cronExpr) return showBox($('#taskResult'), '任务名/表名/cron 必填', 'error');

  $('#btnAddTask').disabled = true;
  try {
    const r = await api('/api/tasks', { method:'POST', body:JSON.stringify({
      name, tableName, connectionId: ci||undefined, cronExpr, cronDesc,
      url: url||undefined, jsonBody: jsonBody||'{}'
    })});
    showBox($('#taskResult'), `✅ 任务已创建: ${r.id}`, 'success');
    $('#taskName').value='';$('#taskTable').value='';$('#taskUrl').value='';$('#taskJsonBody').value='';
    loadTasks();
  } catch(e) { showBox($('#taskResult'), '❌ '+e.message, 'error'); }
  finally { $('#btnAddTask').disabled = false; }
}

async function toggleTask(id, enable) {
  try {
    await fetch(API_BASE+`/api/tasks/${id}/toggle`, { method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify({enabled:enable}) });
    loadTasks();
  } catch(e) { alert('操作失败: '+e.message); }
}

async function triggerTask(id) {
  try {
    await fetch(API_BASE+`/api/tasks/${id}/trigger`, { method:'POST' });
    alert('任务已触发');
    loadTasks();
  } catch(e) { alert('触发失败: '+e.message); }
}

async function delTask(id) {
  if (!confirm('确定删除此任务？')) return;
  try { await fetch(API_BASE+`/api/tasks/${id}`, {method:'DELETE'}); loadTasks(); }
  catch(e) { alert('删除失败: '+e.message); }
}

document.getElementById('btnAddTask')?.addEventListener('click', addTask);
