(function(){
const app=document.querySelector('#app'),modalRoot=document.querySelector('#modal-root');
const state={me:null,csrf:null,surface:localStorage.getItem('nf_surface')||'client',view:'home',workspaces:[],workspace:null,productId:null,productTab:'overview',theme:localStorage.getItem('nf_theme')||'dark',lang:localStorage.getItem('nf_lang')||'en'};
const I={client:[['home','fa-house','Home'],['products','fa-box-open','My Products'],['licenses','fa-key','Licenses'],['keymasters','fa-fingerprint','Keymasters'],['marketplace','fa-store','Marketplace'],['purchases','fa-receipt','Purchases'],['support','fa-headset','Support'],['account','fa-user-gear','Account']],dev:[['overview','fa-chart-line','Overview'],['products','fa-cubes','Products'],['purchases','fa-comments-dollar','Purchases'],['store','fa-shop','Store'],['docs','fa-book-open','Docs & Website'],['integrations','fa-plug','Integrations'],['team','fa-users','Team'],['infra','fa-server','Infrastructure'],['audit','fa-clock-rotate-left','Audit'],['settings','fa-gear','Settings']]};
const h=v=>String(v==null?'':v).replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));
const money=(c,cur)=>new Intl.NumberFormat(undefined,{style:'currency',currency:cur||'EUR'}).format(Number(c||0)/100);
async function api(path,opt){opt=opt||{};const headers={'Content-Type':'application/json',...(opt.headers||{})};if(state.csrf&&opt.method&&opt.method!=='GET')headers['X-CSRF-Token']=state.csrf;const r=await fetch('/api/v2'+path,{credentials:'same-origin',...opt,headers});if(r.status===401){state.me=null;renderAuth();throw new Error('unauthorized')}const raw=await r.text();let data={};if(raw){try{data=JSON.parse(raw)}catch(e){data={message:raw}}}if(!r.ok)throw new Error(data.message||data.error||('HTTP '+r.status));return data}
function toast(msg){let s=document.querySelector('.toast-stack');if(!s){s=document.createElement('div');s.className='toast-stack';document.body.appendChild(s)}const t=document.createElement('div');t.className='toast';t.textContent=msg;s.appendChild(t);setTimeout(()=>t.remove(),2800)}
function modal(title,body,submit){modalRoot.innerHTML='<div class="modal-layer"><div class="modal-card"><div class="modal-head"><h2>'+h(title)+'</h2><button class="icon-btn" data-close><i class="fa-solid fa-xmark"></i></button></div><div class="modal-body">'+body+'</div><div class="modal-foot"><button class="btn" data-close><i class="fa-solid fa-xmark"></i> Cancel</button><button class="btn primary" id="modal-save"><i class="fa-solid fa-check"></i> Save</button></div></div></div>';modalRoot.querySelectorAll('[data-close]').forEach(x=>x.onclick=()=>modalRoot.innerHTML='');document.querySelector('#modal-save').onclick=async()=>{try{await submit(new FormData(modalRoot.querySelector('form')));modalRoot.innerHTML=''}catch(e){toast(e.message)}}}
function formVal(fd,k){return String(fd.get(k)||'').trim()}
async function boot(){
  document.documentElement.dataset.theme=state.theme;
  renderBootSplash();
  const started=Date.now();
  try{
    setBootStatus('Checking Forge runtime','fa-microchip');
    const setup=await api('/setup/status');
    setBootStatus(setup.needs_setup?'Preparing initial setup':'Loading your workspace',setup.needs_setup?'fa-wand-magic-sparkles':'fa-layer-group');
    const wait=Math.max(0,1350-(Date.now()-started));
    if(wait)await new Promise(r=>setTimeout(r,wait));
    await dismissBootSplash();
    if(setup.needs_setup){renderSetup();return}
    const m=await api('/me');state.me=m.user;state.csrf=m.csrf;await loadWorkspaces();
    if(state.surface==='dev'){
      state.workspace=state.workspace||state.workspaces.find(w=>w.status==='active')||state.workspaces[0]||null;
      state.view='overview';
      if(!state.workspace){state.surface='client';state.view='home'}
    }
    let pending=null;try{pending=JSON.parse(localStorage.getItem('nf_workspace_provisioning')||'null')}catch(e){}
    if(pending&&pending.id){
      const w=state.workspaces.find(x=>String(x.id)===String(pending.id))||pending;
      await runWorkspaceProvisioning(w);
      await loadWorkspaces();
      state.workspace=state.workspaces.find(x=>String(x.id)===String(pending.id))||null;
      state.surface='dev';state.view='overview';
    }
    renderShell();loadView()
  }catch(e){
    const wait=Math.max(0,900-(Date.now()-started));if(wait)await new Promise(r=>setTimeout(r,wait));
    await dismissBootSplash();
    if(!state.me)renderAuth()
  }
}
function renderBootSplash(){
  app.innerHTML='<div class="nord-boot" id="nord-boot">'+
    '<div class="nord-boot-noise"></div>'+
    '<div class="nord-boot-center">'+
      '<div class="nord-boot-mark"><div class="nord-boot-mark-inner"><i class="fa-solid fa-cube"></i></div><span class="nord-boot-pulse"></span></div>'+
      '<div class="nord-boot-brand"><strong>NORD</strong><span>FORGE</span></div>'+
      '<div class="nord-boot-version">Developer Platform · V2</div>'+
      '<div class="nord-boot-loader"><span></span></div>'+
      '<div class="nord-boot-status" id="nord-boot-status"><i class="fa-solid fa-bolt"></i><span>Starting Nord Forge</span></div>'+
    '</div>'+
    '<div class="nord-boot-foot"><span>nord-lab</span><span class="nord-boot-dot"></span><span>secure runtime</span></div>'+
  '</div>';
}
function setBootStatus(label,icon){
  const el=document.querySelector('#nord-boot-status');
  if(!el)return;
  el.classList.remove('swap');void el.offsetWidth;el.classList.add('swap');
  el.innerHTML='<i class="fa-solid '+icon+'"></i><span>'+h(label)+'</span>';
}
function dismissBootSplash(){
  return new Promise(resolve=>{
    const el=document.querySelector('#nord-boot');
    if(!el){resolve();return}
    el.classList.add('leaving');
    setTimeout(resolve,520);
  });
}
function renderAuth(){app.innerHTML='<div class="auth-page"><section class="auth-pane"><div class="auth-card"><div class="auth-brand"><div class="rail-mark"><i class="fa-solid fa-code"></i></div><div><b>Nord Forge</b><div class="side-kicker">Developer Platform V2</div></div></div><h1>Welcome back.</h1><p>Sign in to your Client Area or Developer Studio.</p><div class="auth-tabs"><button class="active" data-auth="login"><i class="fa-solid fa-right-to-bracket"></i> Sign in</button><button data-auth="register"><i class="fa-solid fa-user-plus"></i> Register</button></div><div id="auth-form"></div></div></section><section class="auth-art"><div class="auth-art-icon"><i class="fa-solid fa-cubes"></i></div><div class="side-kicker">Nord Forge V2</div><h2>Build, ship and manage your ecosystem.</h2><p>Client Area, Developer Studio and isolated Administration stay separated while products, licensing, releases and support remain connected.</p></section></div>';document.querySelectorAll('[data-auth]').forEach(b=>b.onclick=()=>{document.querySelectorAll('[data-auth]').forEach(x=>x.classList.toggle('active',x===b));authForm(b.dataset.auth)});authForm('login')}
function authForm(mode){const host=document.querySelector('#auth-form');host.innerHTML='<form class="form">'+(mode==='register'?'<div class="field"><label><i class="fa-solid fa-user"></i> Display name</label><div class="input-icon"><i class="fa-solid fa-user"></i><input name="display_name" autocomplete="name" required></div></div>':'')+'<div class="field"><label><i class="fa-solid fa-envelope"></i> Email</label><div class="input-icon"><i class="fa-solid fa-envelope"></i><input name="email" type="email" autocomplete="email" required></div></div><div class="field"><label><i class="fa-solid fa-lock"></i> Password</label><div class="input-icon"><i class="fa-solid fa-lock"></i><input name="password" type="password" minlength="10" autocomplete="'+(mode==='login'?'current-password':'new-password')+'" required></div></div><button class="btn primary auth-submit" type="submit"><i class="fa-solid '+(mode==='login'?'fa-right-to-bracket':'fa-user-plus')+'"></i> '+(mode==='login'?'Sign in':'Create account')+'</button></form>';host.querySelector('form').onsubmit=async e=>{e.preventDefault();const fd=new FormData(e.currentTarget);try{const d=await api('/auth/'+mode,{method:'POST',body:JSON.stringify({email:formVal(fd,'email'),password:formVal(fd,'password'),display_name:formVal(fd,'display_name')})});state.me=d.user;state.csrf=d.csrf;await loadWorkspaces();renderShell();loadView()}catch(err){toast(err.message)}}}
function renderSetup(){
  const setup={step:1,name:'',email:'',password:''};
  const render=()=>{
    const strength=passwordStrength(setup.password);
    app.innerHTML='<div class="setup-shell">'+
      '<aside class="setup-sidebar">'+
        '<div class="setup-brand"><div class="setup-logo"><i class="fa-solid fa-cube"></i></div><div><strong>Nord Forge</strong><span>Developer Platform V2</span></div></div>'+
        '<div class="setup-side-copy"><div class="setup-eyebrow"><i class="fa-solid fa-sparkles"></i> First launch</div><h1>Configure your Forge.</h1><p>A clean workspace for products, releases, licensing and administration starts here.</p></div>'+
        '<div class="setup-visual">'+
          '<div class="setup-orbit orbit-a"></div><div class="setup-orbit orbit-b"></div>'+
          '<div class="setup-core"><i class="fa-solid fa-code-branch"></i></div>'+
          '<div class="setup-node node-a"><i class="fa-solid fa-user-shield"></i><span>Owner</span></div>'+
          '<div class="setup-node node-b"><i class="fa-solid fa-cubes"></i><span>Workspaces</span></div>'+
          '<div class="setup-node node-c"><i class="fa-solid fa-key"></i><span>Licensing</span></div>'+
        '</div>'+
        '<div class="setup-side-foot"><span><i class="fa-solid fa-circle-check"></i> Java runtime ready</span><span><i class="fa-solid fa-shield-halved"></i> Clean V2 database</span></div>'+
      '</aside>'+
      '<main class="setup-main">'+
        '<header class="setup-topbar"><div class="setup-progress-copy"><span>Initial setup</span><strong>Step '+setup.step+' of 3</strong></div><div class="setup-progress">'+[1,2,3].map(i=>'<span class="'+(i<=setup.step?'active':'')+'"></span>').join('')+'</div></header>'+
        '<section class="setup-stage">'+
          '<div class="setup-step '+(setup.step===1?'active':'')+'" data-step="1">'+
            '<div class="setup-step-icon"><i class="fa-solid fa-user-astronaut"></i></div>'+
            '<div class="setup-kicker">Platform identity</div><h2>Create the Platform Owner</h2><p>This account controls Administration and owns the first Forge environment.</p>'+
            '<div class="setup-form-grid">'+
              '<div class="setup-field full"><label><i class="fa-solid fa-user"></i> Display name</label><div class="setup-input"><i class="fa-solid fa-user"></i><input id="setup-name" value="'+h(setup.name)+'" placeholder="Ricardo" autocomplete="name"></div><small>Shown across Forge and Administration.</small></div>'+
              '<div class="setup-field full"><label><i class="fa-solid fa-envelope"></i> Email address</label><div class="setup-input"><i class="fa-solid fa-envelope"></i><input id="setup-email" value="'+h(setup.email)+'" type="email" placeholder="you@nord-lab.io" autocomplete="email"></div><small>Used to sign in to the platform.</small></div>'+
            '</div>'+
          '</div>'+
          '<div class="setup-step '+(setup.step===2?'active':'')+'" data-step="2">'+
            '<div class="setup-step-icon"><i class="fa-solid fa-shield-halved"></i></div>'+
            '<div class="setup-kicker">Account security</div><h2>Secure the owner account</h2><p>Use a strong password. This account has access to the global Administration service.</p>'+
            '<div class="setup-form-grid">'+
              '<div class="setup-field full"><label><i class="fa-solid fa-lock"></i> Password</label><div class="setup-input"><i class="fa-solid fa-lock"></i><input id="setup-password" value="'+h(setup.password)+'" type="password" minlength="10" placeholder="At least 10 characters" autocomplete="new-password"><button type="button" class="setup-eye" id="setup-eye"><i class="fa-solid fa-eye"></i></button></div></div>'+
              '<div class="password-meter"><div class="password-meter-bar"><span style="width:'+strength.percent+'%"></span></div><div><strong>'+strength.label+'</strong><span>'+strength.help+'</span></div></div>'+
              '<div class="security-grid">'+
                '<div class="'+(setup.password.length>=10?'ok':'')+'"><i class="fa-solid '+(setup.password.length>=10?'fa-check':'fa-minus')+'"></i><span>10+ characters</span></div>'+
                '<div class="'+(/[A-Z]/.test(setup.password)?'ok':'')+'"><i class="fa-solid '+(/[A-Z]/.test(setup.password)?'fa-check':'fa-minus')+'"></i><span>Uppercase</span></div>'+
                '<div class="'+(/[0-9]/.test(setup.password)?'ok':'')+'"><i class="fa-solid '+(/[0-9]/.test(setup.password)?'fa-check':'fa-minus')+'"></i><span>Number</span></div>'+
                '<div class="'+(/[^A-Za-z0-9]/.test(setup.password)?'ok':'')+'"><i class="fa-solid '+(/[^A-Za-z0-9]/.test(setup.password)?'fa-check':'fa-minus')+'"></i><span>Symbol</span></div>'+
              '</div>'+
            '</div>'+
          '</div>'+
          '<div class="setup-step '+(setup.step===3?'active':'')+'" data-step="3">'+
            '<div class="setup-step-icon success"><i class="fa-solid fa-rocket"></i></div>'+
            '<div class="setup-kicker">Ready to launch</div><h2>Review your Forge</h2><p>Once created, this account becomes the protected Platform Owner.</p>'+
            '<div class="setup-review">'+
              '<div><span>Owner</span><strong>'+h(setup.name||'Not set')+'</strong></div>'+
              '<div><span>Email</span><strong>'+h(setup.email||'Not set')+'</strong></div>'+
              '<div><span>Role</span><strong>Platform Owner</strong></div>'+
              '<div><span>Database</span><strong>Forge V2 · Clean</strong></div>'+
            '</div>'+
            '<div class="setup-ready"><i class="fa-solid fa-circle-check"></i><div><strong>Everything is ready.</strong><span>Forge will create your account and open the Client Area.</span></div></div>'+
          '</div>'+
        '</section>'+
        '<footer class="setup-actions">'+
          '<button class="setup-btn secondary" id="setup-back" '+(setup.step===1?'disabled':'')+'><i class="fa-solid fa-arrow-left"></i> Back</button>'+
          '<div class="setup-action-right">'+(setup.step<3?
            '<button class="setup-btn primary" id="setup-next">Continue <i class="fa-solid fa-arrow-right"></i></button>':
            '<button class="setup-btn primary launch" id="setup-create"><i class="fa-solid fa-wand-magic-sparkles"></i> Create Forge</button>')+'</div>'+
        '</footer>'+
      '</main>'+
    '</div>';

    const name=document.querySelector('#setup-name'),email=document.querySelector('#setup-email'),password=document.querySelector('#setup-password');
    if(name)name.oninput=e=>setup.name=e.target.value;
    if(email)email.oninput=e=>setup.email=e.target.value;
    if(password)password.oninput=e=>{
      setup.password=e.target.value;
      updatePasswordUi(setup.password);
    };
    const eye=document.querySelector('#setup-eye');
    if(eye)eye.onclick=()=>{const p=document.querySelector('#setup-password');const show=p.type==='password';p.type=show?'text':'password';eye.innerHTML='<i class="fa-solid '+(show?'fa-eye-slash':'fa-eye')+'"></i>'};
    const back=document.querySelector('#setup-back');
    if(back)back.onclick=()=>{if(setup.step>1){setup.step--;render()}};
    const next=document.querySelector('#setup-next');
    if(next)next.onclick=async()=>{
      if(setup.step===1){
        if(setup.name.trim().length<2)return toast('Enter a display name.');
        if(!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(setup.email.trim()))return toast('Enter a valid email address.');
      }
      if(setup.step===2&&setup.password.length<10)return toast('Password must have at least 10 characters.');
      setup.step++;render();
    };
    const create=document.querySelector('#setup-create');
    if(create)create.onclick=async()=>{
      create.disabled=true;create.classList.add('loading');create.innerHTML='<i class="fa-solid fa-circle-notch fa-spin"></i> Creating Forge...';
      try{
        const d=await api('/auth/register',{method:'POST',body:JSON.stringify({email:setup.email.trim(),password:setup.password,display_name:setup.name.trim()})});
        state.me=d.user;state.csrf=d.csrf;state.surface='client';state.view='home';await loadWorkspaces();renderShell();loadView();
      }catch(err){toast(err.message);create.disabled=false;create.classList.remove('loading');create.innerHTML='<i class="fa-solid fa-wand-magic-sparkles"></i> Create Forge'}
    };
  };
  render();
}
function updatePasswordUi(password){
  const strength=passwordStrength(password);
  const meter=document.querySelector('.password-meter-bar span');
  if(meter)meter.style.width=strength.percent+'%';
  const copy=document.querySelector('.password-meter>div:last-child');
  if(copy)copy.innerHTML='<strong>'+h(strength.label)+'</strong><span>'+h(strength.help)+'</span>';

  const rules=[
    password.length>=10,
    /[A-Z]/.test(password),
    /[0-9]/.test(password),
    /[^A-Za-z0-9]/.test(password)
  ];
  document.querySelectorAll('.security-grid>div').forEach((el,i)=>{
    const ok=!!rules[i];
    el.classList.toggle('ok',ok);
    const icon=el.querySelector('i');
    if(icon)icon.className='fa-solid '+(ok?'fa-check':'fa-minus');
  });
}
function passwordStrength(password){
  let score=0;
  if(password.length>=10)score++;
  if(password.length>=14)score++;
  if(/[A-Z]/.test(password)&&/[a-z]/.test(password))score++;
  if(/[0-9]/.test(password))score++;
  if(/[^A-Za-z0-9]/.test(password))score++;
  if(!password)return {percent:0,label:'No password yet',help:'Add a strong password to continue.'};
  if(score<=1)return {percent:24,label:'Weak',help:'Add length, numbers and symbols.'};
  if(score===2)return {percent:48,label:'Fair',help:'A little more complexity will help.'};
  if(score===3)return {percent:68,label:'Good',help:'Good start. You can make it stronger.'};
  if(score===4)return {percent:84,label:'Strong',help:'Strong enough for an owner account.'};
  return {percent:100,label:'Excellent',help:'Excellent password strength.'};
}
async function loadWorkspaces(){try{state.workspaces=await api('/workspaces');if(state.workspace){state.workspace=state.workspaces.find(w=>String(w.id)===String(state.workspace.id))||null}if(!state.workspace&&state.workspaces.length)state.workspace=state.workspaces.find(w=>w.status==='active')||state.workspaces[0]}catch(e){state.workspaces=[];state.workspace=null}}
function workspaceRail(){
  const items=state.workspaces.filter(w=>w.status==='active');
  return items.map(w=>{
    const selected=state.surface==='dev'&&state.workspace&&String(state.workspace.id)===String(w.id);
    const initials=(w.name||'W').trim().split(/\s+/).slice(0,2).map(x=>x[0]||'').join('').toUpperCase();
    return '<button class="rail-workspace '+(selected?'active':'')+'" data-workspace-rail="'+w.id+'" title="'+h(w.name)+'"><span>'+h(initials||'W')+'</span></button>';
  }).join('');
}
function sideNavMarkup(){
  if(state.surface!=='dev'){
    return I.client.map(x=>'<button class="nav-btn '+(state.view===x[0]?'active':'')+'" data-view="'+x[0]+'"><i class="fa-solid '+x[1]+'"></i>'+h(x[2])+'</button>').join('');
  }
  const groups=[
    ['',I.dev.filter(x=>x[0]==='overview')],
    ['Products',I.dev.filter(x=>x[0]==='products')],
    ['Commerce',I.dev.filter(x=>['purchases','store'].includes(x[0]))],
    ['Content',I.dev.filter(x=>['docs','integrations'].includes(x[0]))],
    ['Workspace',I.dev.filter(x=>['team','infra','audit','settings'].includes(x[0]))]
  ];
  return groups.map(g=>(g[0]?'<div class="nav-group-label">'+h(g[0])+'</div>':'')+g[1].map(x=>'<button class="nav-btn '+(state.view===x[0]?'active':'')+'" data-view="'+x[0]+'"><i class="fa-solid '+x[1]+'"></i>'+h(x[2])+'</button>').join('')).join('');
}
function renderShell(){
  localStorage.setItem('nf_surface',state.surface);
  const validViews=(I[state.surface]||[]).map(x=>x[0]);
  if(!validViews.includes(state.view)) state.view=state.surface==='dev'?'overview':'home';
  if(state.surface==='dev'&&!state.workspace&&state.workspaces.length){
    state.workspace=state.workspaces.find(w=>w.status==='active')||state.workspaces[0];
  }
  if(state.surface==='dev'&&!state.workspace){
    state.surface='client';state.view='home';
  }
  const items=I[state.surface];
  const title=state.surface==='client'?'Client Area':'Developer Studio';
  app.innerHTML='<div class="app-shell">'+
    '<aside class="app-rail">'+
      '<button class="rail-mark" title="Nord Forge">N</button>'+
      '<button class="rail-btn '+(state.surface==='client'?'active':'')+'" data-surface="client" title="Client Area"><i class="fa-solid fa-house"></i></button>'+
      '<div class="rail-sep"></div>'+
      '<div class="rail-workspaces">'+workspaceRail()+
        '<button class="rail-add-workspace" id="rail-add-workspace" title="Create workspace"><i class="fa-solid fa-plus"></i></button>'+
      '</div>'+
      '<div class="rail-spacer"></div>'+
      (state.me.platform_owner?'<button class="rail-btn" id="admin-launch" title="Administration"><i class="fa-solid fa-shield-halved"></i></button>':'')+
      '<button class="rail-btn" id="theme" title="Theme"><i class="fa-solid fa-circle-half-stroke"></i></button>'+
      '<button class="rail-btn" id="logout" title="Sign out"><i class="fa-solid fa-arrow-right-from-bracket"></i></button>'+
      '<div class="rail-avatar">'+h((state.me.display_name||state.me.email).slice(0,2).toUpperCase())+'</div>'+
    '</aside>'+
    '<aside class="side">'+
      '<div class="side-head"><div class="side-kicker">'+h(title)+'</div><div class="side-title">'+(state.surface==='client'?'Nord Forge':h(state.workspace.name))+'</div><div class="side-sub">'+(state.surface==='client'?h(state.me.email):'Role · '+h(state.workspace.my_role))+'</div></div>'+
      '<nav class="side-nav">'+sideNavMarkup()+'</nav>'+
      '<div class="side-foot">Nord Forge V2 · Java Runtime</div>'+
    '</aside>'+
    '<section class="main"><header class="topbar"><div><div class="crumb">'+h(title)+(state.surface==='dev'?' / '+h(state.workspace.name):'')+'</div><div class="page-name" id="page-name">Loading</div></div><div class="top-actions"></div></header><main class="content" id="content"></main></section>'+
  '</div>';
  document.querySelectorAll('[data-surface]').forEach(b=>b.onclick=()=>switchSurface(b.dataset.surface));
  document.querySelectorAll('[data-workspace-rail]').forEach(b=>b.onclick=()=>openWorkspaceFromRail(b.dataset.workspaceRail));
  document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>{state.view=b.dataset.view;if(state.view==='products'){state.productId=null;state.productTab='overview'}else{state.productId=null}renderShell();loadView()});
  document.querySelector('#rail-add-workspace').onclick=createWorkspace;
  if(document.querySelector('#admin-launch'))document.querySelector('#admin-launch').onclick=launchAdmin;
  document.querySelector('#theme').onclick=toggleTheme;
  document.querySelector('#logout').onclick=logout;
}
function openWorkspaceFromRail(id){
  const w=state.workspaces.find(x=>String(x.id)===String(id));
  if(!w)return;
  state.workspace=w;state.surface='dev';state.view='overview';state.productId=null;state.productTab='overview';
  localStorage.setItem('nf_surface','dev');
  renderShell();loadView();
}
function switchSurface(s){
  if(s==='client'){state.surface='client';state.view='home'}
  else if(state.workspaces.length){state.surface='dev';state.workspace=state.workspace||state.workspaces[0];state.view='overview'}
  else return createWorkspace();
  localStorage.setItem('nf_surface',state.surface);renderShell();loadView();
}
function toggleTheme(){state.theme=state.theme==='dark'?'light':'dark';document.documentElement.dataset.theme=state.theme;localStorage.setItem('nf_theme',state.theme)}
async function logout(){try{await api('/auth/logout',{method:'POST',body:'{}'})}catch(e){}state.me=null;state.csrf=null;renderAuth()}
async function launchAdmin(){try{const d=await api('/admin/launch');window.open(d.url,'_blank','noopener')}catch(e){toast(e.message)}}
function setTitle(t){const el=document.querySelector('#page-name');if(el)el.textContent=t}
function content(html){document.querySelector('#content').innerHTML=html}
function empty(icon,title,text){return '<div class="empty"><i class="fa-solid '+icon+'"></i><strong>'+h(title)+'</strong><span>'+h(text)+'</span></div>'}
function metrics(rows){return '<div class="metric-grid">'+rows.map(x=>'<div class="metric"><div class="label">'+h(x[0])+'</div><span class="value">'+h(x[1])+'</span><div class="hint">'+h(x[2]||'')+'</div></div>').join('')+'</div>'}
function table(rows,cols){if(!rows||!rows.length)return empty('fa-inbox','Nothing here yet','The first items will appear here.');return '<div class="table-wrap"><table class="table"><thead><tr>'+cols.map(c=>'<th>'+h(c[1])+'</th>').join('')+'</tr></thead><tbody>'+rows.map(r=>'<tr>'+cols.map(c=>'<td>'+cell(r[c[0]],c[0])+'</td>').join('')+'</tr>').join('')+'</tbody></table></div>'}
function cell(v,k){if(k==='status'||k==='entitlement_status')return '<span class="pill '+(v==='active'||v==='published'||v==='granted'?'good':v==='open'?'warn':'')+'">'+h(v)+'</span>';if(k.includes('price_cents'))return h(money(v));return h(v)}
async function loadView(){
  const c=document.querySelector('#content');
  if(c)c.innerHTML='<div class="page-loading"><div class="page-loading-spinner"><i class="fa-solid fa-circle-notch fa-spin"></i></div><strong>Loading</strong><span>Preparing this view…</span></div>';
  try{
    if(state.surface==='client')await loadClient();
    else await loadDev();
  }catch(e){
    setTitle('Unable to load');
    content('<div class="page-error"><div class="page-error-icon"><i class="fa-solid fa-triangle-exclamation"></i></div><div><h2>This page could not be loaded</h2><p>'+h(e.message||'Unexpected Forge error')+'</p><button class="btn" id="retry-view"><i class="fa-solid fa-rotate-right"></i> Retry</button></div></div>');
    const retry=document.querySelector('#retry-view');if(retry)retry.onclick=loadView;
  }
}
async function loadClient(){if(state.view==='home'){setTitle('Home');const [p,l,m]=await Promise.all([api('/client/products'),api('/client/licenses'),api('/marketplace')]);return content('<div class="hero"><div><h1>Welcome back, '+h(state.me.display_name)+'.</h1><p>Your purchases, licenses and Forge Key live here. Developer tooling stays in Developer Studio.</p></div></div>'+metrics([['Owned products',p.length,'Active entitlements'],['Licenses',l.length,'Runtime licenses'],['Marketplace',m.length,'Published products'],['Workspaces',state.workspaces.length,'Developer access']])+'<div class="panel"><div class="panel-head"><div><h2>Your Forge Key</h2><p>Used by compatible licensed resources.</p></div></div><div class="panel-body"><div class="key-box"><code>'+h(state.me.forge_key)+'</code><button class="btn" id="copy-key">Copy</button></div></div></div>');}
if(state.view==='products'){setTitle('My Products');const rows=await api('/client/products');content('<div class="hero"><div><h1>My Products</h1><p>Products granted or purchased through Forge.</p></div></div><div class="cards">'+(rows.length?rows.map(productCard).join(''):empty('fa-box-open','No products yet','Browse the marketplace to add products.'))+'</div>');document.querySelectorAll('[data-download]').forEach(b=>b.onclick=()=>makeDownload(b.dataset.download));return}
if(state.view==='licenses'){setTitle('Licenses');const rows=await api('/client/licenses');return content('<div class="hero"><div><h1>Licenses</h1><p>Runtime entitlement status and server limits.</p></div></div><div class="panel">'+table(rows,[['product_name','Product'],['license_key','License key'],['server_limit','Servers'],['status','Status'],['created_at','Created']])+'</div>')}
if(state.view==='keymasters'){setTitle('Keymasters');const d=await api('/license/keymasters');content('<div class="hero"><div><h1>Keymasters</h1><p>Bind your FiveM Keymaster identities to this Forge account. Raw cfxk values are never stored.</p></div><button class="btn primary" id="keymaster-new">Add Keymaster</button></div>'+metrics([['Active slots',d.slots_used,'Bound identities'],['Slot limit',d.slots_max,'Per Forge account']])+'<div class="panel">'+table(d.keymasters,[['display_name','Name'],['hostname','Hostname'],['status','Status'],['first_seen_at','Added'],['last_seen_at','Last seen']])+'</div>');document.querySelector('#keymaster-new').onclick=addKeymaster;return}
if(state.view==='marketplace'){setTitle('Marketplace');const rows=await api('/marketplace');content('<div class="hero"><div><h1>Marketplace</h1><p>Published products with a valid release.</p></div></div><div class="cards">'+(rows.length?rows.map(marketCard).join(''):empty('fa-store','Marketplace is empty','Published products will appear here.'))+'</div>');document.querySelectorAll('[data-buy]').forEach(b=>b.onclick=()=>buy(b.dataset.buy));return}
if(state.view==='purchases'){setTitle('Purchases');const rows=await api('/client/purchases');return content('<div class="hero"><div><h1>Purchases</h1><p>Manual and provider checkout requests.</p></div></div><div class="panel">'+table(rows,[['product_name','Product'],['workspace_name','Seller'],['provider','Provider'],['status','Status'],['created_at','Created']])+'</div>')}
if(state.view==='support'){setTitle('Support');const rows=await api('/client/support');content('<div class="hero"><div><h1>Support</h1><p>Product support stays tied to the seller workspace.</p></div><button class="btn primary" id="support-new">New ticket</button></div><div class="panel">'+table(rows,[['subject','Subject'],['product_name','Product'],['workspace_name','Workspace'],['priority','Priority'],['status','Status'],['created_at','Created']])+'</div>');document.querySelector('#support-new').onclick=createSupport;return}
if(state.view==='account'){setTitle('Account');return content('<div class="hero"><div><h1>Account</h1><p>Identity and Forge credentials.</p></div></div><div class="split"><div class="panel"><div class="panel-head"><h2>Profile</h2></div><div class="panel-body"><div class="field"><label>Name</label><input value="'+h(state.me.display_name)+'" disabled></div><div class="field" style="margin-top:10px"><label>Email</label><input value="'+h(state.me.email)+'" disabled></div></div></div><div class="panel"><div class="panel-head"><h2>Forge Key</h2></div><div class="panel-body"><div class="key-box"><code>'+h(state.me.forge_key)+'</code></div></div></div></div>')}}
function productCard(p){return '<article class="card"><h3>'+h(p.name)+'</h3><p>'+h(p.description||'No description.')+'</p><div class="meta"><span class="pill">'+h(p.workspace_name)+'</span><span class="pill good">'+h(p.entitlement_status)+'</span></div>'+(p.latest_release_id?'<div style="margin-top:12px"><button class="btn primary" data-download="'+p.latest_release_id+'"><i class="fa-solid fa-download"></i> Download latest</button></div>':'')+'</article>'}
function marketCard(p){return '<article class="card"><h3>'+h(p.name)+'</h3><p>'+h(p.description||'No description.')+'</p><div class="meta"><span class="pill">'+h(p.workspace_name)+'</span><span class="pill">'+h(p.category||'resource')+'</span><span class="pill good">'+money(p.price_cents,p.currency)+'</span></div><div style="margin-top:12px"><button class="btn primary" data-buy="'+p.id+'"><i class="fa-solid fa-cart-shopping"></i> '+(Number(p.price_cents)===0?'Get product':'Request purchase')+'</button></div></article>'}
async function makeDownload(id){try{const d=await api('/client/releases/'+id+'/token',{method:'POST',body:'{}'});location.href=d.url}catch(e){toast(e.message)}}
async function buy(id){try{const d=await api('/purchases',{method:'POST',body:JSON.stringify({product_id:Number(id),message:'Purchase request from Client Area'})});toast(d.granted?'Product added to your account.':'Purchase conversation created.');loadView()}catch(e){toast(e.message)}}
function createSupport(){modal('New support ticket','<form class="form"><div class="field"><label>Subject</label><input name="subject" required></div><div class="field"><label>Priority</label><select name="priority"><option>normal</option><option>high</option><option>low</option></select></div><div class="field"><label>Message</label><textarea name="message" rows="6" required></textarea></div></form>',async fd=>{await api('/client/support',{method:'POST',body:JSON.stringify({subject:formVal(fd,'subject'),priority:formVal(fd,'priority'),message:formVal(fd,'message')})});toast('Ticket created');loadView()})}
async function loadDev(){
if(!state.workspace){state.surface='client';state.view='home';renderShell();return loadClient()}const wid=state.workspace.id;
if(state.view==='overview'){
  setTitle('Overview');
  const d=await api('/workspaces/'+wid+'/dashboard');
  content(
    '<div class="workspace-page">'+
      '<section class="workspace-overview-head">'+
        '<div class="workspace-overview-title">'+
          '<div class="workspace-symbol">'+h((d.workspace.name||'W').slice(0,2).toUpperCase())+'</div>'+
          '<div><div class="side-kicker">Developer workspace</div><h1>'+h(d.workspace.name)+'</h1><p>Products, releases, licensing and runtime operations in one workspace.</p></div>'+
        '</div>'+
        '<div class="workspace-head-actions"><span class="pill '+(d.workspace.status==='active'?'good':'warn')+'"><i class="fa-solid fa-circle"></i> '+h(d.workspace.status)+'</span><span class="workspace-role-chip"><i class="fa-solid fa-user-shield"></i> '+h(d.role)+'</span></div>'+
      '</section>'+
      '<section class="workspace-kpis">'+
        '<button class="workspace-kpi" data-jump="products"><i class="fa-solid fa-cubes"></i><div><span>Products</span><strong>'+h(d.products)+'</strong><small>'+h(d.published_products)+' published</small></div></button>'+
        '<button class="workspace-kpi" data-jump="products"><i class="fa-solid fa-key"></i><div><span>Licenses</span><strong>'+h(d.licenses)+'</strong><small>Active runtime licenses</small></div></button>'+
        '<button class="workspace-kpi" data-jump="team"><i class="fa-solid fa-users"></i><div><span>Members</span><strong>'+h(d.members)+'</strong><small>Workspace team</small></div></button>'+
        '<button class="workspace-kpi" data-jump="purchases"><i class="fa-solid fa-comments-dollar"></i><div><span>Open work</span><strong>'+h(Number(d.open_support||0)+Number(d.open_purchases||0))+'</strong><small>'+h(d.open_support)+' support · '+h(d.open_purchases)+' purchases</small></div></button>'+
      '</section>'+
      '<div class="workspace-overview-grid">'+
        '<section class="panel workspace-main-panel">'+
          '<div class="panel-head"><div><h2>Workspace operations</h2><p>Jump directly into the areas you use most.</p></div></div>'+
          '<div class="workspace-quick-grid">'+
            '<button data-jump="products"><i class="fa-solid fa-cubes"></i><span><strong>Products</strong><small>Catalog and publishing</small></span><i class="fa-solid fa-chevron-right"></i></button>'+
            '<button data-jump="products"><i class="fa-solid fa-code-branch"></i><span><strong>Releases</strong><small>Versions and artifacts</small></span><i class="fa-solid fa-chevron-right"></i></button>'+
            '<button data-jump="products"><i class="fa-solid fa-shield-halved"></i><span><strong>Protection</strong><small>Runtime security</small></span><i class="fa-solid fa-chevron-right"></i></button>'+
            '<button data-jump="integrations"><i class="fa-solid fa-plug"></i><span><strong>Integrations</strong><small>External services</small></span><i class="fa-solid fa-chevron-right"></i></button>'+
            '<button data-jump="docs"><i class="fa-solid fa-book-open"></i><span><strong>Docs & Website</strong><small>Documentation and pages</small></span><i class="fa-solid fa-chevron-right"></i></button>'+
            '<button data-jump="infra"><i class="fa-solid fa-server"></i><span><strong>Infrastructure</strong><small>Nodes and API access</small></span><i class="fa-solid fa-chevron-right"></i></button>'+
          '</div>'+
        '</section>'+
        '<aside class="workspace-overview-side">'+
          '<section class="panel">'+
            '<div class="panel-head"><div><h2>Workspace status</h2><p>Current boundary and access state.</p></div></div>'+
            '<div class="workspace-status-list">'+
              '<div><span>Role</span><strong>'+h(d.role)+'</strong></div>'+
              '<div><span>Status</span><strong>'+h(d.workspace.status)+'</strong></div>'+
              '<div><span>Published</span><strong>'+h(d.published_products)+' / '+h(d.products)+'</strong></div>'+
              '<div><span>Members</span><strong>'+h(d.members)+'</strong></div>'+
            '</div>'+
          '</section>'+
        '</aside>'+
      '</div>'+
    '</div>'
  );
  document.querySelectorAll('[data-jump]').forEach(b=>b.onclick=()=>{state.view=b.dataset.jump;renderShell();loadView()});
  return;
}
if(state.view==='products'){
  if(state.productId){
    setTitle('Product');
    const d=await api('/products/'+state.productId+'/workspace');
    return renderProductWorkspace(d);
  }
  setTitle('Products');
  const rows=await api('/workspaces/'+wid+'/products');
  content(
    '<div class="product-catalog">'+
      '<div class="hero product-catalog-hero"><div><div class="side-kicker">Product catalog</div><h1>Products</h1><p>Manage each product, its releases, licensing and runtime protection from one place.</p></div><button class="btn primary" id="product-new"><i class="fa-solid fa-plus"></i> New product</button></div>'+
      (rows.length?'<div class="product-catalog-grid">'+rows.map(devProductCard).join('')+'</div>':empty('fa-cubes','No products','Create the first product in this workspace.'))+
    '</div>'
  );
  document.querySelector('#product-new').onclick=createProduct;
  document.querySelectorAll('[data-manage-product]').forEach(b=>b.onclick=()=>openProductWorkspace(b.dataset.manageProduct));
  document.querySelectorAll('[data-publish]').forEach(b=>b.onclick=e=>{e.stopPropagation();publishProduct(b.dataset.publish)});
  return;
}
if(state.view==='purchases'){setTitle('Purchase inbox');const rows=await api('/workspaces/'+wid+'/purchases');content('<div class="hero"><div><h1>Purchases</h1><p>Manual checkout requests and customer conversations.</p></div></div><div class="panel">'+table(rows,[['product_name','Product'],['buyer_email','Buyer'],['provider','Provider'],['status','Status'],['created_at','Created']])+'</div>');return}
if(state.view==='store'){setTitle('Store');const s=await api('/workspaces/'+wid+'/store');content('<div class="hero"><div><h1>'+h(s.store_name||s.name)+'</h1><p>Public storefront identity and workspace presentation.</p></div><div><button class="btn" id="store-preview"><i class="fa-solid fa-arrow-up-right-from-square"></i> Preview</button> <button class="btn primary" id="store-edit">Edit store</button></div></div><div class="split"><div class="panel"><div class="panel-head"><h2>Store identity</h2></div><div class="panel-body"><div class="field"><label>Slug</label><input value="'+h(s.slug)+'" disabled></div><div class="field" style="margin-top:10px"><label>Currency</label><input value="'+h(s.store_currency)+'" disabled></div><div class="field" style="margin-top:10px"><label>Theme</label><input value="'+h(s.store_theme)+'" disabled></div></div></div><div class="panel"><div class="panel-head"><h2>Description</h2></div><div class="panel-body"><p style="color:var(--muted);line-height:1.7">'+h(s.store_description||'No store description yet.')+'</p></div></div></div>');document.querySelector('#store-edit').onclick=()=>editStore(s);document.querySelector('#store-preview').onclick=()=>window.open('/store.html?slug='+encodeURIComponent(s.slug),'_blank','noopener');return}
if(state.view==='docs'){setTitle('Docs & Website');const [docs,pages]=await Promise.all([api('/workspaces/'+wid+'/docs'),api('/workspaces/'+wid+'/pages')]);content('<div class="hero"><div><h1>Docs & Website</h1><p>Documentation and public page content live in the same workspace.</p></div><div><button class="btn" id="doc-new">New doc</button> <button class="btn primary" id="page-new">New page</button></div></div><div class="split"><div class="panel"><div class="panel-head"><h2>Documentation</h2></div>'+table(docs,[['title','Title'],['slug','Slug'],['published','Published'],['updated_at','Updated']])+'</div><div class="panel"><div class="panel-head"><h2>Website pages</h2></div>'+table(pages,[['title','Title'],['slug','Slug'],['theme','Theme'],['published','Published']])+'</div></div>');document.querySelector('#doc-new').onclick=createDoc;document.querySelector('#page-new').onclick=createPage;return}
if(state.view==='integrations'){setTitle('Integrations');const [items,tebex,discord]=await Promise.all([api('/workspaces/'+wid+'/integrations'),api('/workspaces/'+wid+'/tebex'),api('/workspaces/'+wid+'/discord')]);content('<div class="hero"><div><h1>Integrations</h1><p>Commerce and community connections scoped to this workspace.</p></div></div><div class="cards"><article class="card"><h3><i class="fa-solid fa-cart-shopping"></i> Tebex</h3><p>Checkout handoff and signed entitlement webhook.</p><div class="meta"><span class="pill '+(tebex.enabled?'good':'')+'">'+(tebex.enabled?'enabled':'not configured')+'</span></div><div style="margin-top:12px"><button class="btn primary" id="tebex-config">Configure</button></div></article><article class="card"><h3><i class="fa-brands fa-discord"></i> Discord</h3><p>Guild resources, bot test messages and workspace connection.</p><div class="meta"><span class="pill '+(discord.enabled?'good':'')+'">'+(discord.enabled?'enabled':'not configured')+'</span></div><div style="margin-top:12px"><button class="btn" id="discord-config">Settings</button> <button class="btn primary" id="discord-connect">Connect</button></div></article></div><div class="panel" style="margin-top:14px"><div class="panel-head"><h2>Workspace integrations</h2></div>'+table(items,[['type','Type'],['enabled','Enabled'],['updated_at','Updated']])+'</div>');document.querySelector('#tebex-config').onclick=()=>configureTebex(tebex);document.querySelector('#discord-config').onclick=()=>configureDiscord(discord);document.querySelector('#discord-connect').onclick=connectDiscord;return}
if(state.view==='team'){setTitle('Team');const rows=await api('/workspaces/'+wid+'/members');content('<div class="hero"><div><h1>Team</h1><p>Workspace access is independent from customer account access.</p></div><button class="btn primary" id="member-new">Add member</button></div><div class="panel">'+table(rows,[['display_name','Name'],['email','Email'],['role','Role'],['status','Status'],['created_at','Added']])+'</div>');document.querySelector('#member-new').onclick=addMember;return}
if(state.view==='infra'){setTitle('Infrastructure');const [nodes,keys,ints]=await Promise.all([api('/workspaces/'+wid+'/infra'),api('/workspaces/'+wid+'/api-keys'),api('/workspaces/'+wid+'/integrations')]);content('<div class="hero"><div><h1>Infrastructure</h1><p>External endpoints, scoped API keys and optional integrations.</p></div><div><button class="btn" id="infra-new">Add node</button> <button class="btn primary" id="key-new">Create API key</button></div></div><div class="panel"><div class="panel-head"><h2>Nodes</h2></div>'+table(nodes,[['name','Name'],['type','Type'],['url','URL'],['status','Status']])+'</div><div class="split"><div class="panel"><div class="panel-head"><h2>API keys</h2></div>'+table(keys,[['name','Name'],['prefix','Prefix'],['scopes','Scopes'],['created_at','Created']])+'</div><div class="panel"><div class="panel-head"><h2>Integrations</h2></div>'+table(ints,[['type','Type'],['enabled','Enabled'],['updated_at','Updated']])+'</div></div>');document.querySelector('#infra-new').onclick=addInfra;document.querySelector('#key-new').onclick=createApiKey;return}
if(state.view==='settings'){
  setTitle('Workspace settings');
  const s=await api('/workspaces/'+wid+'/settings');
  content(
    '<div class="workspace-settings-page">'+
      '<div class="hero settings-hero"><div><div class="side-kicker">Workspace settings</div><h1>'+h(s.name)+'</h1><p>Manage workspace identity, defaults and lifecycle from one place.</p></div><span class="pill '+(s.status==='active'?'good':'warn')+'">'+h(s.status)+'</span></div>'+
      '<div class="workspace-settings-grid">'+
        '<section class="panel"><div class="panel-head"><div><h2>General</h2><p>Workspace identity used across Nord Forge.</p></div></div><div class="panel-body">'+
          '<form id="workspace-settings-form" class="form">'+
            '<div class="split">'+
              '<div class="field"><label>Name</label><input name="name" value="'+h(s.name)+'" '+(!s.can_manage?'disabled':'')+'></div>'+
              '<div class="field"><label>Slug</label><input name="slug" value="'+h(s.slug)+'" '+(!s.can_manage?'disabled':'')+'></div>'+
            '</div>'+
            '<div class="field"><label>Store name</label><input name="store_name" value="'+h(s.store_name||'')+'" '+(!s.can_manage?'disabled':'')+'></div>'+
            '<div class="field"><label>Store description</label><textarea name="store_description" rows="4" '+(!s.can_manage?'disabled':'')+'>'+h(s.store_description||'')+'</textarea></div>'+
            '<div class="split">'+
              '<div class="field"><label>Currency</label><select name="store_currency" '+(!s.can_manage?'disabled':'')+'><option '+(s.store_currency==='EUR'?'selected':'')+'>EUR</option><option '+(s.store_currency==='USD'?'selected':'')+'>USD</option><option '+(s.store_currency==='GBP'?'selected':'')+'>GBP</option></select></div>'+
              '<div class="field"><label>Theme</label><select name="store_theme" '+(!s.can_manage?'disabled':'')+'><option value="dark" '+(s.store_theme==='dark'?'selected':'')+'>Dark</option><option value="light" '+(s.store_theme==='light'?'selected':'')+'>Light</option></select></div>'+
            '</div>'+
            (s.can_manage?'<div class="settings-actions"><button class="btn primary" type="submit"><i class="fa-solid fa-floppy-disk"></i> Save changes</button></div>':'')+
          '</form>'+
        '</div></section>'+
        '<aside class="workspace-settings-side">'+
          '<section class="panel"><div class="panel-head"><div><h2>Workspace details</h2><p>Current access and lifecycle information.</p></div></div><div class="workspace-status-list">'+
            '<div><span>Role</span><strong>'+h(s.role)+'</strong></div>'+
            '<div><span>Status</span><strong>'+h(s.status)+'</strong></div>'+
            '<div><span>Workspace ID</span><strong>#'+h(s.id)+'</strong></div>'+
            '<div><span>Created</span><strong>'+h(s.created_at)+'</strong></div>'+
          '</div></section>'+
        '</aside>'+
      '</div>'+
      (s.is_owner?'<section class="panel danger-zone settings-danger-zone"><div class="panel-head"><div><h2>Danger zone</h2><p>Permanently delete this workspace and all scoped Forge data.</p></div><button class="btn danger" id="delete-workspace"><i class="fa-solid fa-trash"></i> Delete workspace</button></div></section>':'')+
    '</div>'
  );
  const form=document.querySelector('#workspace-settings-form');
  if(form&&s.can_manage)form.onsubmit=async e=>{
    e.preventDefault();
    const fd=new FormData(form);
    try{
      const updated=await api('/workspaces/'+wid+'/settings',{method:'PUT',body:JSON.stringify({
        name:formVal(fd,'name'),slug:formVal(fd,'slug'),store_name:formVal(fd,'store_name'),
        store_description:formVal(fd,'store_description'),store_currency:formVal(fd,'store_currency'),store_theme:formVal(fd,'store_theme')
      })});
      await loadWorkspaces();
      state.workspace=state.workspaces.find(w=>String(w.id)===String(wid))||state.workspace;
      toast('Workspace settings saved');
      renderShell();state.view='settings';loadView();
    }catch(err){toast(err.message)}
  };
  if(document.querySelector('#delete-workspace'))document.querySelector('#delete-workspace').onclick=deleteCurrentWorkspace;
  return;
}
if(state.view==='audit'){setTitle('Audit');const rows=await api('/workspaces/'+wid+'/audit');return content('<div class="hero"><div><h1>Audit</h1><p>Workspace mutations and security-relevant actions.</p></div></div><div class="panel">'+table(rows,[['action','Action'],['display_name','Actor'],['target','Target'],['details','Details'],['created_at','Time']])+'</div>')}
state.view='overview';
renderShell();
return loadView();
}
function devProductCard(p){
  return '<article class="product-catalog-card" data-manage-product="'+p.id+'">'+
    '<div class="product-card-top"><div class="product-card-icon"><i class="fa-solid fa-cube"></i></div><span class="pill '+(p.status==='published'?'good':'warn')+'">'+h(p.status)+'</span></div>'+
    '<h3>'+h(p.name)+'</h3><p>'+h(p.description||'No description yet.')+'</p>'+
    '<div class="product-card-meta"><span><i class="fa-solid fa-tag"></i> '+h(p.category||'resource')+'</span><span><i class="fa-solid fa-shield-halved"></i> '+h(p.protection_mode||'LICENSE_ONLY')+'</span></div>'+
    '<div class="product-card-foot"><strong>'+money(p.price_cents,p.currency)+'</strong><div class="product-card-actions">'+(p.status!=='published'?'<button class="btn" data-publish="'+p.id+'">Publish</button>':'')+'<button class="btn primary" data-manage-product="'+p.id+'">Manage <i class="fa-solid fa-arrow-right"></i></button></div></div>'+
  '</article>';
}
function openProductWorkspace(id){
  state.productId=Number(id);state.productTab='overview';state.view='products';renderShell();loadView();
}
function productTabs(){
  const tabs=[['overview','fa-chart-line','Overview'],['releases','fa-code-branch','Releases'],['licensing','fa-key','Licensing'],['protection','fa-shield-halved','Protection'],['settings','fa-gear','Settings']];
  return '<div class="product-tabs">'+tabs.map(t=>'<button class="'+(state.productTab===t[0]?'active':'')+'" data-product-tab="'+t[0]+'"><i class="fa-solid '+t[1]+'"></i> '+t[2]+'</button>').join('')+'</div>';
}
function renderProductWorkspace(d){
  const p=d.product,releases=d.releases||[],licenses=d.licenses||[],installs=d.installations||[],protection=d.protection||{},builds=d.builds||[];
  setTitle(p.name);
  let body='';
  if(state.productTab==='overview'){
    const latest=releases[0];
    body=
      '<div class="product-workspace-kpis">'+
        '<div><span>Latest release</span><strong>'+(latest?h(latest.version):'—')+'</strong><small>'+(latest?h(latest.created_at):'No releases yet')+'</small></div>'+
        '<div><span>Active licenses</span><strong>'+h(d.active_licenses||0)+'</strong><small>'+h(d.entitlements||0)+' entitlements</small></div>'+
        '<div><span>Installations</span><strong>'+h(d.active_installations||0)+'</strong><small>'+h(installs.length)+' known installs</small></div>'+
        '<div><span>Protection</span><strong>'+h(protection.protection_level||'Not configured')+'</strong><small>'+h(p.protection_mode||'LICENSE_ONLY')+'</small></div>'+
      '</div>'+
      '<div class="product-workspace-grid"><section class="panel"><div class="panel-head"><div><h2>Product activity</h2><p>Recent releases and product state.</p></div></div><div class="product-summary-list">'+
        '<div><span>Status</span><strong>'+h(p.status)+'</strong></div><div><span>Category</span><strong>'+h(p.category||'resource')+'</strong></div><div><span>Price</span><strong>'+money(p.price_cents,p.currency)+'</strong></div><div><span>Purchases</span><strong>'+h(d.purchases||0)+'</strong></div>'+
      '</div></section>'+
      '<section class="panel"><div class="panel-head"><div><h2>Quick actions</h2><p>Continue managing this product.</p></div></div><div class="product-action-list">'+
        '<button data-product-tab="releases"><i class="fa-solid fa-cloud-arrow-up"></i><span><strong>Upload release</strong><small>Publish a new product version</small></span><i class="fa-solid fa-chevron-right"></i></button>'+
        '<button data-product-tab="licensing"><i class="fa-solid fa-key"></i><span><strong>Manage licensing</strong><small>Grant and revoke customer access</small></span><i class="fa-solid fa-chevron-right"></i></button>'+
        '<button data-product-tab="protection"><i class="fa-solid fa-shield-halved"></i><span><strong>Runtime protection</strong><small>Configure builds and installations</small></span><i class="fa-solid fa-chevron-right"></i></button>'+
      '</div></section></div>';
  }else if(state.productTab==='releases'){
    body='<section class="panel"><div class="panel-head"><div><h2>Releases</h2><p>Versions, ZIP artifacts and publication state.</p></div><button class="btn primary" id="product-release-new"><i class="fa-solid fa-cloud-arrow-up"></i> Upload release</button></div>'+
      (releases.length?'<div class="product-data-list">'+releases.map(r=>'<div class="product-data-row"><div class="product-data-main"><i class="fa-solid fa-code-branch"></i><div><strong>v'+h(r.version)+'</strong><span>'+h(r.file_name||'No file')+' · '+h(r.created_at)+'</span></div></div><div class="product-data-actions"><span class="pill '+(r.published?'good':'')+'">'+(r.published?'published':'draft')+'</span><a class="btn" href="/api/v2/releases/'+r.id+'/direct-download"><i class="fa-solid fa-download"></i></a><button class="btn danger" data-delete-release="'+r.id+'"><i class="fa-solid fa-trash"></i></button></div></div>').join('')+'</div>':empty('fa-code-branch','No releases','Upload the first release for this product.'))+
      '</section>';
  }else if(state.productTab==='licensing'){
    body='<section class="panel"><div class="panel-head"><div><h2>Licensing</h2><p>Customer licenses scoped to '+h(p.name)+'.</p></div><button class="btn primary" id="product-license-new"><i class="fa-solid fa-plus"></i> Grant license</button></div>'+
      (licenses.length?'<div class="product-data-list">'+licenses.map(l=>'<div class="product-data-row"><div class="product-data-main"><i class="fa-solid fa-key"></i><div><strong>'+h(l.display_name||l.email)+'</strong><span>'+h(l.email)+' · '+h(l.license_key)+'</span></div></div><div class="product-data-actions"><span class="pill '+(l.status==='active'?'good':'warn')+'">'+h(l.status)+'</span><span class="product-limit">'+h(l.server_limit)+' server'+(Number(l.server_limit)===1?'':'s')+'</span>'+(l.status==='active'?'<button class="btn danger" data-revoke-license="'+l.id+'" data-limit="'+l.server_limit+'"><i class="fa-solid fa-ban"></i> Revoke</button>':'')+'</div></div>').join('')+'</div>':empty('fa-key','No licenses','Grant the first customer license for this product.'))+
      '</section>';
  }else if(state.productTab==='protection'){
    body='<div class="product-workspace-grid"><section class="panel"><div class="panel-head"><div><h2>Protection policy</h2><p>Runtime validation and build identity.</p></div><button class="btn primary" id="product-protection-config"><i class="fa-solid fa-sliders"></i> Configure</button></div><div class="product-summary-list">'+
      '<div><span>Level</span><strong>'+h(protection.protection_level||'Not configured')+'</strong></div><div><span>Heartbeat</span><strong>'+h(protection.heartbeat_interval_seconds||'—')+'</strong></div><div><span>Fingerprint</span><strong>'+(protection.fingerprint_binding===true?'Bound':'—')+'</strong></div><div><span>Builds</span><strong>'+h(builds.length)+'</strong></div>'+
      '</div></section><section class="panel"><div class="panel-head"><div><h2>Installations</h2><p>Known runtime installations for this product.</p></div></div>'+
      (installs.length?'<div class="product-data-list compact">'+installs.map(i=>'<div class="product-data-row"><div class="product-data-main"><i class="fa-solid fa-server"></i><div><strong>'+h(i.resource_id||'Runtime')+'</strong><span>'+h(i.user_email||'')+' · '+h(i.version||'—')+'</span></div></div><div class="product-data-actions"><span class="pill '+(i.status==='active'?'good':'warn')+'">'+h(i.status)+'</span>'+(i.status==='active'?'<button class="btn danger" data-revoke-install="'+h(i.installation_id)+'"><i class="fa-solid fa-ban"></i></button>':'')+'</div></div>').join('')+'</div>':empty('fa-server','No installations','Protected runtime installations will appear here.'))+
      '</section></div>';
  }else if(state.productTab==='settings'){
    body='<div class="product-settings-grid"><section class="panel"><div class="panel-head"><div><h2>Product settings</h2><p>Identity, commerce and runtime behavior.</p></div></div><div class="panel-body"><form class="form" id="product-settings-form">'+
      '<div class="split"><div class="field"><label>Name</label><input name="name" value="'+h(p.name)+'" required></div><div class="field"><label>Category</label><input name="category" value="'+h(p.category||'')+'"></div></div>'+
      '<div class="field"><label>Description</label><textarea name="description" rows="5">'+h(p.description||'')+'</textarea></div>'+
      '<div class="split"><div class="field"><label>Price cents</label><input name="price_cents" type="number" min="0" value="'+h(p.price_cents||0)+'"></div><div class="field"><label>Currency</label><select name="currency"><option '+(p.currency==='EUR'?'selected':'')+'>EUR</option><option '+(p.currency==='USD'?'selected':'')+'>USD</option><option '+(p.currency==='GBP'?'selected':'')+'>GBP</option></select></div></div>'+
      '<div class="split"><div class="field"><label>Protection mode</label><select name="protection_mode"><option '+(p.protection_mode==='LICENSE_ONLY'?'selected':'')+'>LICENSE_ONLY</option><option '+(p.protection_mode==='NONE'?'selected':'')+'>NONE</option><option '+(p.protection_mode==='PROTECTED_BUILD'?'selected':'')+'>PROTECTED_BUILD</option></select></div><div class="field"><label>Status</label><div class="published-state"><i class="fa-solid fa-circle-check"></i><span><strong>Published</strong><small>Products with a published release stay published.</small></span></div><input type="hidden" name="status" value="published"></div></div>'+
      '<label class="product-check"><input name="license_required" type="checkbox" '+(p.license_required?'checked':'')+'> <span><strong>License required</strong><small>Require a valid Forge license at runtime.</small></span></label>'+
      '<div class="settings-actions"><button class="btn primary" type="submit"><i class="fa-solid fa-floppy-disk"></i> Save product</button></div></form></div></section>'+
      '<section class="panel danger-zone product-danger"><div class="panel-head"><div><h2>Danger zone</h2><p>Permanently remove this product, releases and runtime data.</p></div><button class="btn danger" id="delete-product"><i class="fa-solid fa-trash"></i> Delete product</button></div></section></div>';
  }
  content('<div class="product-workspace"><header class="product-workspace-head"><button class="product-back" id="product-back"><i class="fa-solid fa-arrow-left"></i></button><div class="product-workspace-mark"><i class="fa-solid fa-cube"></i></div><div class="product-workspace-title"><div class="side-kicker">Product workspace</div><h1>'+h(p.name)+'</h1><div class="product-workspace-meta"><span class="pill '+(p.status==='published'?'good':'warn')+'">'+h(p.status)+'</span><span>'+h(p.category||'resource')+'</span><span>'+money(p.price_cents,p.currency)+'</span></div></div></header>'+productTabs()+'<div class="product-tab-body">'+body+'</div></div>');
  document.querySelector('#product-back').onclick=()=>{state.productId=null;state.productTab='overview';loadView()};
  document.querySelectorAll('[data-product-tab]').forEach(b=>b.onclick=()=>{state.productTab=b.dataset.productTab;loadView()});
  const releaseNew=document.querySelector('#product-release-new');if(releaseNew)releaseNew.onclick=()=>createRelease([p]);
  document.querySelectorAll('[data-delete-release]').forEach(b=>b.onclick=()=>deleteProductRelease(b.dataset.deleteRelease));
  const licenseNew=document.querySelector('#product-license-new');if(licenseNew)licenseNew.onclick=()=>grantProductLicense(p.id);
  document.querySelectorAll('[data-revoke-license]').forEach(b=>b.onclick=()=>revokeProductLicense(b.dataset.revokeLicense,b.dataset.limit));
  const protect=document.querySelector('#product-protection-config');if(protect)protect.onclick=()=>configureProductProtection(p,protection);
  document.querySelectorAll('[data-revoke-install]').forEach(b=>b.onclick=()=>revokeProductInstallation(b.dataset.revokeInstall));
  const settings=document.querySelector('#product-settings-form');if(settings)settings.onsubmit=e=>saveProductSettings(e,p);
  const del=document.querySelector('#delete-product');if(del)del.onclick=()=>deleteCurrentProduct(p);
}
async function deleteProductRelease(id){
  try{await api('/releases/'+id,{method:'DELETE',body:'{}'});toast('Release deleted');loadView()}catch(e){toast(e.message)}
}
function grantProductLicense(pid){
  modal('Grant product license','<form class="form"><div class="field"><label>Customer email</label><input name="email" type="email" required></div></form>',async fd=>{await api('/workspaces/'+state.workspace.id+'/licenses',{method:'POST',body:JSON.stringify({email:formVal(fd,'email'),product_id:Number(pid)})});toast('License granted');loadView()});
}
async function revokeProductLicense(id,limit){
  try{await api('/workspaces/'+state.workspace.id+'/licenses/'+id,{method:'PUT',body:JSON.stringify({status:'revoked',server_limit:Number(limit||1)})});toast('License revoked');loadView()}catch(e){toast(e.message)}
}
function configureProductProtection(p,current){
  modal('Protection policy','<form class="form"><div class="field"><label>Protection level</label><select name="protection_level"><option '+(current.protection_level==='standard'?'selected':'')+'>standard</option><option '+(current.protection_level==='protected'?'selected':'')+'>protected</option><option '+(current.protection_level==='streamed'?'selected':'')+'>streamed</option></select></div><div class="split"><div class="field"><label>Heartbeat seconds</label><input name="heartbeat_interval_seconds" type="number" value="'+h(current.heartbeat_interval_seconds||300)+'" min="30" max="3600"></div><div class="field"><label>Grace period</label><input name="grace_period_seconds" type="number" value="'+h(current.grace_period_seconds||900)+'" min="0" max="86400"></div></div><div class="split"><div class="field"><label>Session TTL</label><input name="session_ttl_seconds" type="number" value="'+h(current.session_ttl_seconds||300)+'" min="60" max="3600"></div><div class="field"><label>Minimum version</label><input name="minimum_version" value="'+h(current.minimum_version||'')+'" placeholder="2.0.0"></div></div><label class="product-check"><input name="fingerprint_binding" type="checkbox" value="true" '+(current.fingerprint_binding!==false?'checked':'')+'><span><strong>Fingerprint binding</strong></span></label><label class="product-check"><input name="integrity_validation" type="checkbox" value="true" '+(current.integrity_validation!==false?'checked':'')+'><span><strong>Integrity validation</strong></span></label></form>',async fd=>{await api('/products/'+p.id+'/protection',{method:'PUT',body:JSON.stringify({protection_level:formVal(fd,'protection_level'),heartbeat_interval_seconds:Number(formVal(fd,'heartbeat_interval_seconds')),grace_period_seconds:Number(formVal(fd,'grace_period_seconds')),session_ttl_seconds:Number(formVal(fd,'session_ttl_seconds')),minimum_version:formVal(fd,'minimum_version'),fingerprint_binding:fd.get('fingerprint_binding')==='true',integrity_validation:fd.get('integrity_validation')==='true',heartbeat_enabled:true,watermarking_enabled:true})});toast('Protection updated');loadView()});
}
async function revokeProductInstallation(id){
  try{await api('/protection/installations/'+encodeURIComponent(id)+'/revoke',{method:'POST',body:JSON.stringify({reason:'revoked_from_product_workspace'})});toast('Installation revoked');loadView()}catch(e){toast(e.message)}
}
async function saveProductSettings(e,p){
  e.preventDefault();const fd=new FormData(e.currentTarget);
  try{
    const updated=await api('/products/'+p.id,{method:'PUT',body:JSON.stringify({name:formVal(fd,'name'),description:formVal(fd,'description'),category:formVal(fd,'category'),price_cents:Number(formVal(fd,'price_cents')||0),currency:formVal(fd,'currency'),license_required:fd.get('license_required')==='on',protection_mode:formVal(fd,'protection_mode'),status:formVal(fd,'status')})});
    toast('Product settings saved');setTitle(updated.name||p.name);loadView();
  }catch(err){toast(err.message)}
}
function deleteCurrentProduct(p){
  modalRoot.innerHTML='<div class="modal-layer"><div class="modal-card"><div class="modal-head"><div><div class="side-kicker">Danger zone</div><h2>Delete '+h(p.name)+'</h2></div><button class="icon-btn" type="button" data-close><i class="fa-solid fa-xmark"></i></button></div><div class="modal-body"><div class="notice">This permanently deletes the product, releases, licenses and protection data.</div><div class="field"><label>Type <b>'+h(p.name)+'</b> to confirm</label><input id="delete-product-confirm" autocomplete="off" spellcheck="false"><small id="delete-product-hint">Enter the exact product name to continue.</small></div></div><div class="modal-foot"><button class="btn" type="button" data-close>Cancel</button><button class="btn danger" type="button" id="delete-product-confirm-btn"><i class="fa-solid fa-trash"></i> Delete permanently</button></div></div></div>';
  modalRoot.querySelectorAll('[data-close]').forEach(x=>x.onclick=()=>modalRoot.innerHTML='');
  const input=modalRoot.querySelector('#delete-product-confirm');
  const btn=modalRoot.querySelector('#delete-product-confirm-btn');
  const hint=modalRoot.querySelector('#delete-product-hint');
  const normalizeDeleteName=v=>String(v||'').trim().replace(/\s+/g,' ').toLocaleLowerCase();
  const matches=()=>normalizeDeleteName(input.value)===normalizeDeleteName(p.name);

  input.oninput=()=>{
    const ok=matches();
    btn.classList.toggle('confirmed',ok);
    if(hint){
      hint.textContent=ok?'Product name confirmed. You can delete it now.':'Enter the exact product name to continue.';
      hint.classList.toggle('ok',ok);
    }
  };

  const remove=async()=>{
    if(!matches()){
      toast('Type the exact product name first.');
      input.focus();
      return;
    }
    try{
      btn.disabled=true;
      btn.innerHTML='<i class="fa-solid fa-circle-notch fa-spin"></i> Deleting...';
      await api('/products/'+p.id,{method:'DELETE',body:'{}'});
      modalRoot.innerHTML='';
      state.productId=null;
      state.productTab='overview';
      toast('Product deleted');
      await loadView();
    }catch(e){
      toast(e.message);
      btn.disabled=false;
      btn.innerHTML='<i class="fa-solid fa-trash"></i> Delete permanently';
    }
  };

  btn.onclick=remove;
  input.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();remove()}};
  setTimeout(()=>input.focus(),0);
}
function createWorkspace(){
  const draft={step:1,name:'',slug:'',store_name:'',currency:'EUR',theme:state.theme==='light'?'light':'dark'};
  const draw=()=>{
    modalRoot.innerHTML='<div class="modal-layer workspace-wizard-layer"><div class="workspace-wizard">'+
      '<header class="workspace-wizard-head"><div><div class="side-kicker">New workspace</div><h2>Configure your workspace</h2></div><button class="icon-btn" id="workspace-wizard-close"><i class="fa-solid fa-xmark"></i></button></header>'+
      '<div class="workspace-wizard-progress">'+[1,2,3].map(i=>'<span class="'+(i<=draft.step?'active':'')+'"></span>').join('')+'</div>'+
      '<div class="workspace-wizard-body">'+
        (draft.step===1?'<div class="wizard-step"><div class="wizard-icon"><i class="fa-solid fa-cube"></i></div><h3>Workspace identity</h3><p>Choose how this workspace is identified inside Nord Forge.</p><div class="field"><label>Name</label><input id="ww-name" value="'+h(draft.name)+'" placeholder="Nord Lab" autofocus></div><div class="field"><label>Slug</label><input id="ww-slug" value="'+h(draft.slug)+'" placeholder="nord-lab"></div></div>':'')+
        (draft.step===2?'<div class="wizard-step"><div class="wizard-icon"><i class="fa-solid fa-sliders"></i></div><h3>Workspace defaults</h3><p>Set the initial store and interface defaults. These can be changed later.</p><div class="field"><label>Store name</label><input id="ww-store" value="'+h(draft.store_name)+'" placeholder="'+h(draft.name||'Nord Lab')+'"></div><div class="split"><div class="field"><label>Currency</label><select id="ww-currency"><option '+(draft.currency==='EUR'?'selected':'')+'>EUR</option><option '+(draft.currency==='USD'?'selected':'')+'>USD</option><option '+(draft.currency==='GBP'?'selected':'')+'>GBP</option></select></div><div class="field"><label>Theme</label><select id="ww-theme"><option value="dark" '+(draft.theme==='dark'?'selected':'')+'>Dark</option><option value="light" '+(draft.theme==='light'?'selected':'')+'>Light</option></select></div></div></div>':'')+
        (draft.step===3?'<div class="wizard-step"><div class="wizard-icon success"><i class="fa-solid fa-wand-magic-sparkles"></i></div><h3>Ready to prepare</h3><p>Forge will create the workspace and then prepare its environment.</p><div class="setup-review"><div><span>Name</span><strong>'+h(draft.name)+'</strong></div><div><span>Slug</span><strong>'+h(draft.slug||'Auto generated')+'</strong></div><div><span>Currency</span><strong>'+h(draft.currency)+'</strong></div><div><span>Theme</span><strong>'+h(draft.theme)+'</strong></div></div></div>':'')+
      '</div>'+
      '<footer class="workspace-wizard-foot"><button class="btn" id="ww-back" '+(draft.step===1?'disabled':'')+'><i class="fa-solid fa-arrow-left"></i> Back</button><button class="btn primary" id="'+(draft.step===3?'ww-create':'ww-next')+'">'+(draft.step===3?'<i class="fa-solid fa-plus"></i> Create workspace':'Continue <i class="fa-solid fa-arrow-right"></i>')+'</button></footer>'+
    '</div></div>';
    document.querySelector('#workspace-wizard-close').onclick=()=>modalRoot.innerHTML='';
    const name=document.querySelector('#ww-name'),slug=document.querySelector('#ww-slug'),store=document.querySelector('#ww-store'),currency=document.querySelector('#ww-currency'),theme=document.querySelector('#ww-theme');
    if(name)name.oninput=e=>draft.name=e.target.value;
    if(slug)slug.oninput=e=>draft.slug=e.target.value;
    if(store)store.oninput=e=>draft.store_name=e.target.value;
    if(currency)currency.onchange=e=>draft.currency=e.target.value;
    if(theme)theme.onchange=e=>draft.theme=e.target.value;
    document.querySelector('#ww-back').onclick=()=>{if(draft.step>1){draft.step--;draw()}};
    const next=document.querySelector('#ww-next');
    if(next)next.onclick=()=>{
      if(draft.step===1&&draft.name.trim().length<2)return toast('Enter a workspace name.');
      if(draft.step===1&&!draft.slug.trim())draft.slug=draft.name.trim().toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');
      if(draft.step===2&&!draft.store_name.trim())draft.store_name=draft.name.trim();
      draft.step++;draw();
    };
    const create=document.querySelector('#ww-create');
    if(create)create.onclick=async()=>{
      create.disabled=true;create.innerHTML='<i class="fa-solid fa-circle-notch fa-spin"></i> Creating...';
      try{
        const w=await api('/workspaces',{method:'POST',body:JSON.stringify({name:draft.name.trim(),slug:draft.slug.trim(),store_name:draft.store_name.trim(),currency:draft.currency,theme:draft.theme})});
        modalRoot.innerHTML='';
        localStorage.setItem('nf_workspace_provisioning',JSON.stringify({id:w.id,name:w.name,started_at:Date.now()}));
        await runWorkspaceProvisioning(w);
        await loadWorkspaces();
        state.workspace=state.workspaces.find(x=>String(x.id)===String(w.id))||w;
        state.surface='dev';state.view='overview';renderShell();loadView();toast('Workspace ready');
      }catch(e){toast(e.message);create.disabled=false;create.innerHTML='<i class="fa-solid fa-plus"></i> Create workspace'}
    };
  };
  draw();
}
async function deleteCurrentWorkspace(){
  if(!state.workspace)return;
  const w=state.workspace;
  modalRoot.innerHTML='<div class="modal-layer"><div class="modal-card"><div class="modal-head"><div><div class="side-kicker">Danger zone</div><h2>Delete '+h(w.name)+'</h2></div><button class="icon-btn" data-close><i class="fa-solid fa-xmark"></i></button></div><div class="modal-body"><div class="notice">This permanently deletes the workspace and its Forge data. This action cannot be undone.</div><div class="field"><label>Type <b>'+h(w.name)+'</b> to confirm</label><input id="delete-workspace-confirm" autocomplete="off"></div></div><div class="modal-foot"><button class="btn" data-close>Cancel</button><button class="btn danger" id="delete-workspace-confirm-btn" disabled><i class="fa-solid fa-trash"></i> Delete permanently</button></div></div></div>';
  modalRoot.querySelectorAll('[data-close]').forEach(x=>x.onclick=()=>modalRoot.innerHTML='');
  const input=document.querySelector('#delete-workspace-confirm'),button=document.querySelector('#delete-workspace-confirm-btn');
  input.oninput=()=>button.disabled=input.value!==w.name;
  button.onclick=async()=>{
    button.disabled=true;button.innerHTML='<i class="fa-solid fa-circle-notch fa-spin"></i> Deleting...';
    try{
      await api('/workspaces/'+w.id,{method:'DELETE',body:JSON.stringify({confirmation:input.value})});
      modalRoot.innerHTML='';state.workspace=null;await loadWorkspaces();
      if(state.workspaces.length){state.workspace=state.workspaces.find(x=>x.status==='active')||state.workspaces[0];state.surface='dev';state.view='overview'}
      else{state.surface='client';state.view='home'}
      renderShell();loadView();toast('Workspace deleted');
    }catch(e){toast(e.message);button.disabled=false;button.innerHTML='<i class="fa-solid fa-trash"></i> Delete permanently'}
  };
}
async function runWorkspaceProvisioning(workspace){
  const total=5*60*1000;
  let saved={id:workspace.id,name:workspace.name,started_at:Date.now()};
  try{saved=JSON.parse(localStorage.getItem('nf_workspace_provisioning')||'null')||saved}catch(e){}
  const started=Number(saved.started_at)||Date.now();
  const stages=[
    [0,'Creating workspace identity','fa-fingerprint'],
    [18,'Preparing development environment','fa-code'],
    [36,'Configuring licensing services','fa-key'],
    [54,'Preparing releases and storage','fa-box-archive'],
    [72,'Linking administration controls','fa-shield-halved'],
    [88,'Finalizing workspace','fa-circle-check']
  ];
  app.innerHTML='<div class="workspace-provision">'+
    '<div class="workspace-provision-grid"></div>'+
    '<div class="workspace-provision-card">'+
      '<div class="workspace-provision-brand"><div class="setup-logo"><i class="fa-solid fa-cube"></i></div><div><strong>NORD FORGE</strong><span>Workspace Provisioning</span></div></div>'+
      '<div class="workspace-provision-spinner"><div class="workspace-provision-ring"></div><div class="workspace-provision-icon" id="workspace-provision-icon"><i class="fa-solid fa-fingerprint"></i></div></div>'+
      '<div class="workspace-provision-copy"><div class="workspace-provision-kicker">Preparing '+h(workspace.name)+'</div><h1>Please wait while we prepare your workspace.</h1><p id="workspace-provision-status">Creating workspace identity</p></div>'+
      '<div class="workspace-provision-progress"><span id="workspace-provision-bar"></span></div>'+
      '<div class="workspace-provision-meta"><span id="workspace-provision-percent">0%</span><span id="workspace-provision-time">About 5 minutes remaining</span></div>'+
      '<div class="workspace-provision-note"><i class="fa-solid fa-circle-info"></i><span>You can keep this tab open. If the page is refreshed, Forge will resume this preparation screen.</span></div>'+
    '</div>'+
  '</div>';

  await new Promise(resolve=>{
    const tick=()=>{
      const elapsed=Math.max(0,Date.now()-started);
      const progress=Math.min(100,(elapsed/total)*100);
      const bar=document.querySelector('#workspace-provision-bar');
      const pct=document.querySelector('#workspace-provision-percent');
      const time=document.querySelector('#workspace-provision-time');
      const status=document.querySelector('#workspace-provision-status');
      const icon=document.querySelector('#workspace-provision-icon');
      if(bar)bar.style.width=progress.toFixed(2)+'%';
      if(pct)pct.textContent=Math.floor(progress)+'%';
      const remain=Math.max(0,total-elapsed);
      const mins=Math.ceil(remain/60000);
      if(time)time.textContent=remain>0?(mins<=1?'Less than a minute remaining':'About '+mins+' minutes remaining'):'Workspace ready';
      let current=stages[0];
      for(const stage of stages)if(progress>=stage[0])current=stage;
      if(status)status.textContent=current[1];
      if(icon)icon.innerHTML='<i class="fa-solid '+current[2]+'"></i>';
      if(progress>=100){
        localStorage.removeItem('nf_workspace_provisioning');
        setTimeout(resolve,700);
      }else setTimeout(tick,500);
    };
    tick();
  });
}

function renderProductProcessing(stateData){
  const existing=modalRoot.querySelector('#product-processing-modal');
  if(!existing){
    modalRoot.innerHTML=
      '<div class="modal-layer product-processing-layer">'+
        '<div class="product-processing-modal" id="product-processing-modal">'+
          '<div class="upload-transfer-visual" id="processing-transfer">'+
            '<div class="transfer-node transfer-pc"><div class="transfer-node-icon"><i class="fa-solid fa-desktop"></i></div><small>Your PC</small></div>'+
            '<div class="transfer-route">'+
              '<svg class="transfer-curve" viewBox="0 0 420 96" preserveAspectRatio="none" aria-hidden="true">'+
                '<path class="transfer-curve-shadow" d="M 6 78 Q 210 4 414 78"></path>'+
                '<path class="transfer-curve-main" d="M 6 78 Q 210 4 414 78"></path>'+
              '</svg>'+
              '<div class="transfer-dots"><span></span><span></span><span></span></div>'+
              '<div class="transfer-file"><i class="fa-solid fa-file-zipper"></i></div>'+
            '</div>'+
            '<div class="transfer-node transfer-server"><div class="transfer-node-icon"><i class="fa-solid fa-server"></i></div><small>Nord Forge</small></div>'+
          '</div>'+
          '<div class="product-processing-kicker">Nord Forge</div>'+
          '<h2 id="processing-title">Preparing upload</h2>'+
          '<p class="product-processing-copy" id="processing-copy">Preparing your release...</p>'+
          '<div class="product-processing-progress">'+
            '<div class="product-processing-progress-top"><span id="processing-status">Preparing your Forge workspace...</span><strong id="processing-percent">0%</strong></div>'+
            '<div class="product-processing-track"><div id="processing-bar" style="width:0%"></div></div>'+
          '</div>'+
          '<div class="product-processing-steps">'+
            ['Upload','Validate','Create','Release','Finish'].map((x,i)=>'<div><span>'+(i+1)+'</span><small>'+x+'</small></div>').join('')+
          '</div>'+
          '<div class="product-processing-error" id="processing-error" hidden></div>'+
          '<button class="btn primary" id="processing-back" hidden><i class="fa-solid fa-arrow-left"></i> Back to wizard</button>'+
        '</div>'+
      '</div>';
  }
  applyProductProcessingState(stateData);
}

function applyProductProcessingState(stateData){
  const modal=modalRoot.querySelector('#product-processing-modal');
  if(!modal)return;

  const phase=stateData.phase||modal.dataset.phase||'uploading';
  const labels={
    validating:['Validating package','Checking ZIP integrity and release metadata...'],
    uploading:['Uploading release','Sending your ZIP securely to Nord Forge...'],
    creating:['Creating product','Preparing the product workspace and metadata...'],
    release:['Publishing first release','Linking the uploaded package to the new product...'],
    finishing:['Finishing setup','Running final checks and preparing your workspace...'],
    success:['Product ready','Everything is configured and ready to use.'],
    error:['Something went wrong','The operation stopped before completion.']
  };
  const item=labels[phase]||labels.uploading;
  modal.dataset.phase=phase;
  modal.className='product-processing-modal '+phase;

  const transfer=modalRoot.querySelector('#processing-transfer');
  if(transfer)transfer.className='upload-transfer-visual '+phase;

  const title=modalRoot.querySelector('#processing-title');
  const copy=modalRoot.querySelector('#processing-copy');
  if(title)title.textContent=item[0];
  if(copy)copy.textContent=item[1];

  const pctValue=stateData.percent!=null?Math.max(0,Math.min(100,Number(stateData.percent))):Number(modal.dataset.percent||0);
  modal.dataset.percent=String(pctValue);
  const bar=modalRoot.querySelector('#processing-bar');
  const pct=modalRoot.querySelector('#processing-percent');
  if(bar)bar.style.width=pctValue+'%';
  if(pct)pct.textContent=Math.round(pctValue)+'%';

  const status=modalRoot.querySelector('#processing-status');
  if(status&&stateData.status)status.textContent=stateData.status;

  const step=stateData.step!=null?Number(stateData.step):Number(modal.dataset.step||1);
  modal.dataset.step=String(step);
  modalRoot.querySelectorAll('.product-processing-steps>div').forEach((el,i)=>{
    el.classList.toggle('done',i+1<step);
    el.classList.toggle('active',i+1===step);
    const span=el.querySelector('span');
    if(span)span.innerHTML=i+1<step?'<i class="fa-solid fa-check"></i>':String(i+1);
  });

  const error=modalRoot.querySelector('#processing-error');
  const back=modalRoot.querySelector('#processing-back');
  if(phase==='error'){
    if(error){error.hidden=false;error.textContent=stateData.error||'Unknown error';}
    if(back){
      back.hidden=false;
      back.onclick=stateData.onBack||(()=>{});
    }
  }else{
    if(error)error.hidden=true;
    if(back)back.hidden=true;
  }
}

function updateProductProcessing(percent,status,step){
  applyProductProcessingState({percent,status,step});
}

function createProduct(){
  const draft={
    step:1,
    name:'',
    slug:'',
    slugTouched:false,
    category:'scripts',
    description:'',
    price_cents:0,
    currency:'EUR',
    protection_mode:'LICENSE_ONLY',
    license_required:true,
    release_version:'1.0.0',
    release_changelog:'',
    release_file:null
  };
  const categories=[
    ['scripts','fa-code','Scripts'],
    ['mlos','fa-building','MLOs'],
    ['components','fa-shirt','Components'],
    ['bots','fa-robot','Bots'],
    ['other','fa-shapes','Other']
  ];
  const protectionOptions=[
    ['NONE','fa-unlock','No protection','No runtime license or protection checks.'],
    ['LICENSE_ONLY','fa-key','License only','Require a valid Forge license at runtime.'],
    ['PROTECTED_BUILD','fa-shield-halved','Protected build','License validation plus Forge runtime protection.']
  ];
  const categoryIcon=()=>((categories.find(x=>x[0]===draft.category)||categories[0])[1]);
  const draw=()=>{
    const price=money(draft.price_cents,draft.currency);
    const protection=protectionOptions.find(x=>x[0]===draft.protection_mode)||protectionOptions[1];
    const releaseFileName=draft.release_file?draft.release_file.name:'No ZIP selected';
    const releaseFileSize=draft.release_file?Math.max(1,Math.round(draft.release_file.size/1024))+' KB':'Choose the first production ZIP';
    modalRoot.innerHTML=
      '<div class="modal-layer product-wizard-layer">'+
        '<div class="product-create-wizard">'+
          '<header class="product-create-head">'+
            '<div><div class="side-kicker">New product</div><h2>Create a Forge product</h2><p>Configure the product and upload its first release before Forge creates it.</p></div>'+
            '<button class="icon-btn" id="product-wizard-close"><i class="fa-solid fa-xmark"></i></button>'+
          '</header>'+
          '<div class="product-create-progress">'+
            [1,2,3,4,5].map((i)=>'<div class="'+(i===draft.step?'current':i<draft.step?'done':'')+'"><span>'+(i<draft.step?'<i class="fa-solid fa-check"></i>':i)+'</span><small>'+['Identity','Commerce','Protection','First Release','Review'][i-1]+'</small></div>').join('')+
          '</div>'+
          '<div class="product-create-layout">'+
            '<section class="product-create-stage">'+
              (draft.step===1?
                '<div class="product-create-step">'+
                  '<div class="wizard-step-heading"><div class="wizard-icon"><i class="fa-solid fa-wand-magic-sparkles"></i></div><div><div class="side-kicker">Step 1</div><h3>Product identity</h3><p>Define how this product appears throughout Forge.</p></div></div>'+
                  '<div class="field"><label>Name</label><input id="pc-name" value="'+h(draft.name)+'" placeholder="Nord Inventory" autofocus></div>'+
                  '<div class="field"><label>Slug</label><div class="input-icon"><i class="fa-solid fa-link"></i><input id="pc-slug" value="'+h(draft.slug)+'" placeholder="nord-inventory"></div><small>Used in URLs and runtime identification.</small></div>'+
                  '<div class="field"><label>Category</label><div class="product-category-grid">'+categories.map(x=>'<button type="button" class="'+(draft.category===x[0]?'active':'')+'" data-product-category="'+x[0]+'"><i class="fa-solid '+x[1]+'"></i><span>'+x[2]+'</span></button>').join('')+'</div></div>'+
                  '<div class="field"><label>Description</label><textarea id="pc-description" rows="4" placeholder="What does this product do?">'+h(draft.description)+'</textarea></div>'+
                '</div>':'')+
              (draft.step===2?
                '<div class="product-create-step">'+
                  '<div class="wizard-step-heading"><div class="wizard-icon"><i class="fa-solid fa-tags"></i></div><div><div class="side-kicker">Step 2</div><h3>Commerce</h3><p>Set the default price and billing currency.</p></div></div>'+
                  '<div class="product-commerce-grid">'+
                    '<button type="button" class="commerce-mode '+(Number(draft.price_cents)===0?'active':'')+'" id="pc-free"><i class="fa-solid fa-gift"></i><span><strong>Free product</strong><small>Customers can claim it without payment.</small></span></button>'+
                    '<button type="button" class="commerce-mode '+(Number(draft.price_cents)>0?'active':'')+'" id="pc-paid"><i class="fa-solid fa-credit-card"></i><span><strong>Paid product</strong><small>Use the configured price in your store.</small></span></button>'+
                  '</div>'+
                  '<div class="split">'+
                    '<div class="field"><label>Price</label><div class="price-input"><input id="pc-price" type="number" min="0" step="1" value="'+h(draft.price_cents)+'"><span>cents</span></div><small id="pc-price-display">'+h(price)+'</small></div>'+
                    '<div class="field"><label>Currency</label><select id="pc-currency"><option '+(draft.currency==='EUR'?'selected':'')+'>EUR</option><option '+(draft.currency==='USD'?'selected':'')+'>USD</option><option '+(draft.currency==='GBP'?'selected':'')+'>GBP</option></select></div>'+
                  '</div>'+
                  '<div class="product-commerce-note"><i class="fa-solid fa-circle-info"></i><div><strong>Product starts as a draft</strong><span>The first release is uploaded during this wizard. You can publish the product afterwards.</span></div></div>'+
                '</div>':'')+
              (draft.step===3?
                '<div class="product-create-step">'+
                  '<div class="wizard-step-heading"><div class="wizard-icon"><i class="fa-solid fa-shield-halved"></i></div><div><div class="side-kicker">Step 3</div><h3>Licensing & protection</h3><p>Choose how Forge protects and validates this product.</p></div></div>'+
                  '<div class="product-protection-options">'+protectionOptions.map(x=>'<button type="button" class="'+(draft.protection_mode===x[0]?'active':'')+'" data-product-protection="'+x[0]+'"><div class="protection-option-icon"><i class="fa-solid '+x[1]+'"></i></div><div><strong>'+x[2]+'</strong><span>'+x[3]+'</span></div><i class="fa-solid fa-circle-check"></i></button>').join('')+'</div>'+
                  '<div class="product-license-summary"><i class="fa-solid '+protection[1]+'"></i><div><strong>'+protection[2]+'</strong><span>'+(draft.protection_mode==='NONE'?'License requirement will be disabled.':'Forge licensing will be enabled for this product.')+'</span></div></div>'+
                '</div>':'')+
              (draft.step===4?
                '<div class="product-create-step">'+
                  '<div class="wizard-step-heading"><div class="wizard-icon"><i class="fa-solid fa-code-branch"></i></div><div><div class="side-kicker">Step 4</div><h3>Create the first release</h3><p>Upload the initial production package so the product is ready to manage immediately.</p></div></div>'+
                  '<div class="split">'+
                    '<div class="field"><label>Version</label><div class="input-icon"><i class="fa-solid fa-tag"></i><input id="pc-release-version" value="'+h(draft.release_version)+'" placeholder="1.0.0"></div></div>'+
                    '<div class="field"><label>Release state</label><div class="release-state-card"><i class="fa-solid fa-circle-check"></i><div><strong>Published release</strong><span>Available as the first product build.</span></div></div></div>'+
                  '</div>'+
                  '<div class="field"><label>ZIP package</label><label class="product-release-drop '+(draft.release_file?'selected':'')+'" for="pc-release-file"><input id="pc-release-file" type="file" accept=".zip"><div class="release-drop-icon"><i class="fa-solid '+(draft.release_file?'fa-file-zipper':'fa-cloud-arrow-up')+'"></i></div><div><strong id="pc-release-file-name">'+h(releaseFileName)+'</strong><span id="pc-release-file-size">'+h(releaseFileSize)+'</span></div><div class="release-drop-action">'+(draft.release_file?'Replace ZIP':'Choose ZIP')+'</div></label></div>'+
                  '<div class="field"><label>Changelog</label><textarea id="pc-release-changelog" rows="4" placeholder="Initial production release...">'+h(draft.release_changelog)+'</textarea></div>'+
                  '<div class="product-commerce-note"><i class="fa-solid fa-box-archive"></i><div><strong>Release is created with the product</strong><span>Forge uploads this ZIP immediately after creating the product record.</span></div></div>'+
                '</div>':'')+
              (draft.step===5?
                '<div class="product-create-step">'+
                  '<div class="wizard-step-heading"><div class="wizard-icon success"><i class="fa-solid fa-rocket"></i></div><div><div class="side-kicker">Step 5</div><h3>Ready to create</h3><p>Review the complete product and its first release.</p></div></div>'+
                  '<div class="product-review-grid">'+
                    '<div><span>Name</span><strong>'+h(draft.name)+'</strong></div>'+
                    '<div><span>Category</span><strong>'+h((categories.find(x=>x[0]===draft.category)||categories[0])[2])+'</strong></div>'+
                    '<div><span>Price</span><strong>'+h(price)+'</strong></div>'+
                    '<div><span>Protection</span><strong>'+h(protection[2])+'</strong></div>'+
                    '<div><span>First release</span><strong>v'+h(draft.release_version)+'</strong></div>'+
                    '<div><span>Package</span><strong>'+h(draft.release_file?draft.release_file.name:'Missing ZIP')+'</strong></div>'+
                  '</div>'+
                  '<div class="product-ready-banner"><i class="fa-solid fa-circle-check"></i><div><strong>Product and release are ready</strong><span>Forge will create both and open the Product Workspace automatically.</span></div></div>'+
                '</div>':'')+
            '</section>'+
            '<aside class="product-create-preview">'+
              '<div class="product-preview-label">Live preview</div>'+
              '<article class="product-preview-card">'+
                '<div class="product-preview-top"><div class="product-preview-icon"><i class="fa-solid '+categoryIcon()+'"></i></div><span class="pill good">published</span></div>'+
                '<h3>'+h(draft.name||'Untitled product')+'</h3>'+
                '<p>'+h(draft.description||'Your product description will appear here.')+'</p>'+
                '<div class="product-preview-meta"><span><i class="fa-solid fa-tag"></i> '+h((categories.find(x=>x[0]===draft.category)||categories[0])[2])+'</span><span><i class="fa-solid '+protection[1]+'"></i> '+h(protection[2])+'</span><span><i class="fa-solid fa-code-branch"></i> v'+h(draft.release_version||'1.0.0')+'</span></div>'+
                '<div class="product-preview-price" id="pc-preview-price">'+h(price)+'</div>'+
              '</article>'+
              '<div class="product-preview-status">'+
                '<div class="'+(draft.name.trim().length>=2?'ok':'')+'"><i class="fa-solid '+(draft.name.trim().length>=2?'fa-check':'fa-minus')+'"></i><span>Product identity</span></div>'+
                '<div class="'+(draft.slug.trim().length>=2?'ok':'')+'"><i class="fa-solid '+(draft.slug.trim().length>=2?'fa-check':'fa-minus')+'"></i><span>Valid slug</span></div>'+
                '<div class="ok"><i class="fa-solid fa-check"></i><span>Runtime policy</span></div>'+
                '<div class="'+(draft.release_file?'ok':'')+'"><i class="fa-solid '+(draft.release_file?'fa-check':'fa-minus')+'"></i><span>First release ZIP</span></div>'+
              '</div>'+
            '</aside>'+
          '</div>'+
          '<footer class="product-create-foot">'+
            '<button class="btn" id="product-wizard-back" '+(draft.step===1?'disabled':'')+'><i class="fa-solid fa-arrow-left"></i> Back</button>'+
            '<div class="product-create-foot-right">'+
              '<span>Step '+draft.step+' of 5</span>'+
              (draft.step<5?'<button class="btn primary" id="product-wizard-next">Continue <i class="fa-solid fa-arrow-right"></i></button>':'<button class="btn primary" id="product-wizard-create"><i class="fa-solid fa-wand-magic-sparkles"></i> Create product & release</button>')+
            '</div>'+
          '</footer>'+
        '</div>'+
      '</div>';

    document.querySelector('#product-wizard-close').onclick=()=>modalRoot.innerHTML='';
    const name=document.querySelector('#pc-name'),slug=document.querySelector('#pc-slug'),desc=document.querySelector('#pc-description');
    if(name)name.oninput=e=>{draft.name=e.target.value;if(!draft.slugTouched)draft.slug=draft.name.toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'')};
    if(slug)slug.oninput=e=>{draft.slugTouched=true;draft.slug=e.target.value.toLowerCase().replace(/[^a-z0-9-]/g,'')};
    if(desc)desc.oninput=e=>draft.description=e.target.value;
    document.querySelectorAll('[data-product-category]').forEach(b=>b.onclick=()=>{draft.category=b.dataset.productCategory;draw()});
    const priceInput=document.querySelector('#pc-price'),currency=document.querySelector('#pc-currency');
    if(priceInput)priceInput.oninput=e=>{
      draft.price_cents=Math.max(0,Number(e.target.value)||0);
      const live=money(draft.price_cents,draft.currency);
      const display=document.querySelector('#pc-price-display');
      const preview=document.querySelector('#pc-preview-price');
      if(display)display.textContent=live;
      if(preview)preview.textContent=live;
      const free=document.querySelector('#pc-free');
      const paid=document.querySelector('#pc-paid');
      if(free)free.classList.toggle('active',Number(draft.price_cents)===0);
      if(paid)paid.classList.toggle('active',Number(draft.price_cents)>0);
    };
    if(currency)currency.onchange=e=>{draft.currency=e.target.value;draw()};
    const free=document.querySelector('#pc-free'),paid=document.querySelector('#pc-paid');
    if(free)free.onclick=()=>{draft.price_cents=0;draw()};
    if(paid)paid.onclick=()=>{if(Number(draft.price_cents)===0)draft.price_cents=1000;draw()};
    document.querySelectorAll('[data-product-protection]').forEach(b=>b.onclick=()=>{draft.protection_mode=b.dataset.productProtection;draft.license_required=draft.protection_mode!=='NONE';draw()});
    const rv=document.querySelector('#pc-release-version'),rc=document.querySelector('#pc-release-changelog'),rf=document.querySelector('#pc-release-file');
    if(rv)rv.oninput=e=>draft.release_version=e.target.value;
    if(rc)rc.oninput=e=>draft.release_changelog=e.target.value;
    if(rf)rf.onchange=e=>{
      const file=e.target.files&&e.target.files[0];
      if(!file)return;
      if(!file.name.toLowerCase().endsWith('.zip')){e.target.value='';return toast('The first release must be a ZIP file.')}
      draft.release_file=file;
      const n=document.querySelector('#pc-release-file-name'),s=document.querySelector('#pc-release-file-size');
      if(n)n.textContent=file.name;
      if(s)s.textContent=Math.max(1,Math.round(file.size/1024))+' KB';
      const drop=document.querySelector('.product-release-drop');if(drop)drop.classList.add('selected');
    };
    document.querySelector('#product-wizard-back').onclick=()=>{if(draft.step>1){draft.step--;draw()}};
    const next=document.querySelector('#product-wizard-next');
    if(next)next.onclick=async()=>{
      if(draft.step===1){
        if(draft.name.trim().length<2)return toast('Enter a product name.');
        if(!draft.slug.trim())draft.slug=draft.name.trim().toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');
        if(draft.slug.length<2)return toast('Enter a valid product slug.');
      }
      if(draft.step===2&&Number(draft.price_cents)<0)return toast('Price cannot be negative.');
      if(draft.step===4){
        if(!draft.release_version.trim())return toast('Enter the first release version.');
        if(!draft.release_file)return toast('Select the ZIP for the first release.');
        try{await validateZipFile(draft.release_file)}catch(err){return toast(err.message)}
      }
      draft.step++;draw();
    };
    const create=document.querySelector('#product-wizard-create');
    if(create)create.onclick=async()=>{
      const friendly={
        release_file_required:'Select the ZIP for the first release.',
        invalid_base64:'The release file could not be encoded.',
        zip_required:'The release file must be a ZIP archive.',
        invalid_zip:'The selected file is not a valid ZIP archive.',
        release_too_large_512mb:'The release ZIP exceeds the 512 MB limit.',
        product_slug_taken:'A published product already uses this slug.',
        release_version_required:'Enter the first release version.'
      };
      try{
        renderProductProcessing({phase:'validating',percent:4,step:1,status:'Checking your release package...'});
        await validateZipFile(draft.release_file);
        updateProductProcessing(8,'ZIP validated. Preparing upload...',1);

        renderProductProcessing({phase:'uploading',percent:8,step:1,status:'Starting secure upload...'});
        const upload=await uploadReleaseBinary(draft.release_file,percent=>{
          const mapped=8+(percent*0.62);
          updateProductProcessing(mapped,'Uploading '+draft.release_file.name+' · '+percent+'%',1);
        });

        renderProductProcessing({phase:'creating',percent:72,step:3,status:'Creating product record and workspace metadata...'});
        await new Promise(r=>setTimeout(r,350));
        updateProductProcessing(78,'Applying commerce and protection settings...',3);

        const result=await api('/workspaces/'+state.workspace.id+'/products/bootstrap',{method:'POST',body:JSON.stringify({
          name:draft.name.trim(),
          slug:draft.slug.trim(),
          category:draft.category,
          description:draft.description.trim(),
          price_cents:Number(draft.price_cents)||0,
          currency:draft.currency,
          license_required:draft.protection_mode!=='NONE',
          protection_mode:draft.protection_mode,
          release_version:draft.release_version.trim(),
          release_file_name:draft.release_file.name,
          release_upload_token:upload.upload_token,
          release_changelog:draft.release_changelog.trim()
        })});

        renderProductProcessing({phase:'release',percent:88,step:4,status:'Publishing v'+draft.release_version.trim()+' and linking the package...'});
        await new Promise(r=>setTimeout(r,450));
        renderProductProcessing({phase:'finishing',percent:96,step:5,status:'Finalizing product workspace...'});
        await new Promise(r=>setTimeout(r,550));

        const product=result.product;
        renderProductProcessing({phase:'success',percent:100,step:6,status:'Product and first release are ready.'});
        await new Promise(r=>setTimeout(r,900));

        modalRoot.innerHTML='';
        state.productId=product.id;
        state.productTab='overview';
        state.view='products';
        toast(result.recovered?'Recovered draft and created first release':'Product and first release created');
        renderShell();loadView();
      }catch(e){
        const message=friendly[e.message]||e.message||'Unknown error';
        renderProductProcessing({
          phase:'error',
          percent:0,
          step:1,
          error:message,
          status:'Creation stopped before completion.',
          onBack:()=>draw()
        });
      }
    };
  };
  draw();
}
async function publishProduct(id){try{const products=await api('/workspaces/'+state.workspace.id+'/products');const p=products.find(x=>String(x.id)===String(id));await api('/products/'+id,{method:'PUT',body:JSON.stringify({name:p.name,description:p.description,category:p.category,price_cents:p.price_cents,currency:p.currency,license_required:p.license_required,protection_mode:p.protection_mode,status:'published'})});toast('Product published');loadView()}catch(e){toast(e.message)}}
function createRelease(products){if(!products.length)return toast('Create a product first.');modal('Upload release','<form class="form"><div class="field"><label>Product</label><select name="product_id">'+products.map(p=>'<option value="'+p.id+'">'+h(p.name)+'</option>').join('')+'</select></div><div class="field"><label>Version</label><input name="version" placeholder="2.0.0" required></div><div class="field"><label>ZIP file</label><input name="file" type="file" accept=".zip" required></div><div class="field"><label>Changelog</label><textarea name="changelog" rows="5"></textarea></div></form>',async fd=>{const file=fd.get('file');if(!file||!file.size)throw new Error('ZIP file required');await validateZipFile(file);const upload=await uploadReleaseBinary(file);await api('/products/'+formVal(fd,'product_id')+'/releases',{method:'POST',body:JSON.stringify({version:formVal(fd,'version'),file_name:file.name,upload_token:upload.upload_token,changelog:formVal(fd,'changelog'),published:true})});toast('Release uploaded');loadView()})}
async function uploadReleaseBinary(file,onProgress){
  await validateZipFile(file);
  return new Promise((resolve,reject)=>{
    const xhr=new XMLHttpRequest();
    xhr.open('POST','/api/v2/workspaces/'+state.workspace.id+'/release-uploads',true);
    xhr.withCredentials=true;
    xhr.setRequestHeader('Content-Type','application/octet-stream');
    if(state.csrf)xhr.setRequestHeader('X-CSRF-Token',state.csrf);
    xhr.setRequestHeader('X-File-Name',encodeURIComponent(file.name));
    xhr.upload.onprogress=e=>{
      if(e.lengthComputable&&onProgress)onProgress(Math.max(1,Math.min(100,Math.round((e.loaded/e.total)*100))));
    };
    xhr.onload=()=>{
      const raw=xhr.responseText||'';
      let data={};
      if(raw){try{data=JSON.parse(raw)}catch(e){data={message:raw}}}
      if(xhr.status>=200&&xhr.status<300)return resolve(data);
      reject(new Error(data.message||data.error||('HTTP '+xhr.status)));
    };
    xhr.onerror=()=>reject(new Error('Release upload connection failed.'));
    xhr.onabort=()=>reject(new Error('Release upload was cancelled.'));
    xhr.send(file);
  });
}
async function validateZipFile(file){
  if(!file||!file.size)throw new Error('Select the ZIP for the first release.');
  if(!file.name.toLowerCase().endsWith('.zip'))throw new Error('The release file must use the .zip extension.');
  if(file.size>512*1024*1024)throw new Error('The release ZIP exceeds the 512 MB limit.');
  const head=new Uint8Array(await file.slice(0,4).arrayBuffer());
  const valid=head.length>=4&&head[0]===0x50&&head[1]===0x4b&&(
    (head[2]===0x03&&head[3]===0x04)||
    (head[2]===0x05&&head[3]===0x06)||
    (head[2]===0x07&&head[3]===0x08)
  );
  if(!valid)throw new Error('The selected file is not a valid ZIP archive.');
  return true;
}
async function grantLicense(){const products=await api('/workspaces/'+state.workspace.id+'/products');modal('Grant license','<form class="form"><div class="field"><label>Customer email</label><input name="email" type="email" required></div><div class="field"><label>Product</label><select name="product_id">'+products.map(p=>'<option value="'+p.id+'">'+h(p.name)+'</option>').join('')+'</select></div></form>',async fd=>{await api('/workspaces/'+state.workspace.id+'/licenses',{method:'POST',body:JSON.stringify({email:formVal(fd,'email'),product_id:Number(formVal(fd,'product_id'))})});toast('License granted');loadView()})}
function createDoc(){modal('Documentation page','<form class="form"><div class="field"><label>Title</label><input name="title" required></div><div class="field"><label>Slug</label><input name="slug"></div><div class="field"><label>Content</label><textarea name="body" rows="12"></textarea></div><div class="field"><label><input name="published" type="checkbox" value="true"> Published</label></div></form>',async fd=>{await api('/workspaces/'+state.workspace.id+'/docs',{method:'POST',body:JSON.stringify({title:formVal(fd,'title'),slug:formVal(fd,'slug'),body:formVal(fd,'body'),published:fd.get('published')==='true'})});toast('Documentation saved');loadView()})}
function createPage(){modal('Website page','<form class="form"><div class="field"><label>Title</label><input name="title" required></div><div class="field"><label>Slug</label><input name="slug"></div><div class="field"><label>Theme</label><select name="theme"><option>light</option><option>dark</option></select></div><div class="field"><label>Layout JSON</label><textarea name="layout_json" rows="12" placeholder="{&quot;blocks&quot;:[]}"></textarea></div><div class="field"><label><input name="published" type="checkbox" value="true"> Published</label></div></form>',async fd=>{await api('/workspaces/'+state.workspace.id+'/pages',{method:'POST',body:JSON.stringify({title:formVal(fd,'title'),slug:formVal(fd,'slug'),theme:formVal(fd,'theme'),layout_json:formVal(fd,'layout_json'),published:fd.get('published')==='true'})});toast('Page saved');loadView()})}
function addMember(){modal('Add team member','<form class="form"><div class="field"><label>User email</label><input name="email" type="email" required></div><div class="field"><label>Role</label><select name="role"><option>developer</option><option>admin</option><option>support</option><option>marketing</option><option>finance</option><option>tester</option><option>viewer</option></select></div><div class="field"><label>Custom permissions</label><input name="permissions" placeholder="products.write,docs.write"></div></form>',async fd=>{await api('/workspaces/'+state.workspace.id+'/members',{method:'POST',body:JSON.stringify({email:formVal(fd,'email'),role:formVal(fd,'role'),permissions:formVal(fd,'permissions')})});toast('Member added');loadView()})}
function addInfra(){modal('Add infrastructure node','<form class="form"><div class="field"><label>Name</label><input name="name" required></div><div class="field"><label>Type</label><select name="type"><option>FiveM Server</option><option>API</option><option>Worker</option><option>Website</option><option>Database</option></select></div><div class="field"><label>URL</label><input name="url"></div></form>',async fd=>{await api('/workspaces/'+state.workspace.id+'/infra',{method:'POST',body:JSON.stringify({name:formVal(fd,'name'),type:formVal(fd,'type'),url:formVal(fd,'url')})});toast('Node added');loadView()})}
function createApiKey(){modal('Create API key','<form class="form"><div class="field"><label>Name</label><input name="name" required></div><div class="field"><label>Scopes</label><input name="scopes" placeholder="licenses.read,releases.read"></div></form>',async fd=>{const d=await api('/workspaces/'+state.workspace.id+'/api-keys',{method:'POST',body:JSON.stringify({name:formVal(fd,'name'),scopes:formVal(fd,'scopes')})});modalRoot.innerHTML='';navigator.clipboard&&navigator.clipboard.writeText(d.key);toast('API key created and copied: '+d.key)})}
function editStore(s){modal('Store settings','<form class="form"><div class="field"><label>Store name</label><input name="store_name" value="'+h(s.store_name||s.name)+'" required></div><div class="field"><label>Description</label><textarea name="store_description" rows="6">'+h(s.store_description||'')+'</textarea></div><div class="split"><div class="field"><label>Currency</label><select name="store_currency"><option>EUR</option><option>USD</option><option>GBP</option></select></div><div class="field"><label>Theme</label><select name="store_theme"><option>dark</option><option>light</option></select></div></div></form>',async fd=>{await api('/workspaces/'+state.workspace.id+'/store',{method:'PUT',body:JSON.stringify({store_name:formVal(fd,'store_name'),store_description:formVal(fd,'store_description'),store_currency:formVal(fd,'store_currency'),store_theme:formVal(fd,'store_theme')})});toast('Store updated');loadView()})}
function configureTebex(current){let cfg={};try{cfg=JSON.parse(current.config_json||'{}')}catch(e){}modal('Tebex integration','<form class="form"><div class="field"><label>Store URL</label><input name="store_url" value="'+h(cfg.store_url||'')+'" placeholder="https://your-store.tebex.io" required></div><div class="field"><label>Webhook secret</label><input name="webhook_secret" type="password" placeholder="'+(cfg.webhook_secret?'Stored · enter to replace':'Required')+'"></div><div class="notice">Webhook endpoint: /api/v2/webhooks/tebex/'+state.workspace.id+'</div></form>',async fd=>{const next={store_url:formVal(fd,'store_url'),webhook_secret:formVal(fd,'webhook_secret')||cfg.webhook_secret||''};await api('/workspaces/'+state.workspace.id+'/tebex',{method:'PUT',body:JSON.stringify({enabled:true,config_json:JSON.stringify(next)})});toast('Tebex integration saved');loadView()})}
function configureDiscord(current){let cfg={};try{cfg=JSON.parse(current.config_json||'{}')}catch(e){}modal('Discord integration','<form class="form"><div class="field"><label>Guild ID</label><input name="guild_id" value="'+h(cfg.guild_id||'')+'" placeholder="Discord server ID"></div><div class="field"><label>Ticket category ID</label><input name="ticket_category_id" value="'+h(cfg.ticket_category_id||'')+'"></div><div class="field"><label>Support role ID</label><input name="support_role_id" value="'+h(cfg.support_role_id||'')+'"></div></form>',async fd=>{const next={guild_id:formVal(fd,'guild_id'),ticket_category_id:formVal(fd,'ticket_category_id'),support_role_id:formVal(fd,'support_role_id')};await api('/workspaces/'+state.workspace.id+'/discord',{method:'PUT',body:JSON.stringify({enabled:true,config_json:JSON.stringify(next)})});toast('Discord settings saved');loadView()})}
async function connectDiscord(){try{const d=await api('/workspaces/'+state.workspace.id+'/discord/invite');window.open(d.url,'_blank','noopener')}catch(e){toast(e.message)}}
function addKeymaster(){modal('Add Keymaster','<form class="form"><div class="notice">The raw Keymaster is accepted once and stored only as a SHA-256 fingerprint.</div><div class="field"><label>Keymaster</label><input name="keymaster" placeholder="cfxk_..." required></div><div class="field"><label>Display name</label><input name="display_name" placeholder="Production server"></div><div class="field"><label>Hostname</label><input name="hostname" placeholder="roleplay.example.com"></div></form>',async fd=>{await api('/license/keymasters',{method:'POST',body:JSON.stringify({keymaster:formVal(fd,'keymaster'),display_name:formVal(fd,'display_name'),hostname:formVal(fd,'hostname')})});toast('Keymaster added');loadView()})}
async function configureProtection(products){if(!products.length)return toast('Create a product first.');const options=products.map(p=>'<option value="'+p.id+'">'+h(p.name)+'</option>').join('');modal('Protection policy','<form class="form"><div class="field"><label>Product</label><select name="product_id">'+options+'</select></div><div class="field"><label>Protection level</label><select name="protection_level"><option>standard</option><option>protected</option><option>streamed</option></select></div><div class="split"><div class="field"><label>Heartbeat seconds</label><input name="heartbeat_interval_seconds" type="number" value="300" min="30" max="3600"></div><div class="field"><label>Grace period</label><input name="grace_period_seconds" type="number" value="900" min="0" max="86400"></div></div><div class="split"><div class="field"><label>Session TTL</label><input name="session_ttl_seconds" type="number" value="300" min="60" max="3600"></div><div class="field"><label>Minimum version</label><input name="minimum_version" placeholder="2.0.0"></div></div><div class="field"><label><input name="fingerprint_binding" type="checkbox" value="true" checked> Bind installation fingerprint</label></div><div class="field"><label><input name="integrity_validation" type="checkbox" value="true" checked> Integrity validation</label></div></form>',async fd=>{const pid=Number(formVal(fd,'product_id'));await api('/products/'+pid+'/protection',{method:'PUT',body:JSON.stringify({protection_level:formVal(fd,'protection_level'),heartbeat_interval_seconds:Number(formVal(fd,'heartbeat_interval_seconds')),grace_period_seconds:Number(formVal(fd,'grace_period_seconds')),session_ttl_seconds:Number(formVal(fd,'session_ttl_seconds')),minimum_version:formVal(fd,'minimum_version'),fingerprint_binding:fd.get('fingerprint_binding')==='true',integrity_validation:fd.get('integrity_validation')==='true',heartbeat_enabled:true,watermarking_enabled:true})});const releases=await api('/products/'+pid+'/releases');if(releases.length){await api('/products/'+pid+'/protection/builds',{method:'POST',body:JSON.stringify({release_id:releases[0].id})});toast('Protection policy saved and a signed build identity was created.')}else toast('Protection saved. Upload a release to create a build identity.');loadView()})}
boot();
})();