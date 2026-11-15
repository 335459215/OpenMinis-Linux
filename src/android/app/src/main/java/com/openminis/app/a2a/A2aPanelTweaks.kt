package com.openminis.app.a2a

/**
 * 面板是编译好的 React 单页应用（a2a-ui.zip），没有前端源码可重编。
 * 这里用运行时补丁改 DOM：只动显隐、尺寸、标签与一次跳转，不碰网关逻辑。
 *
 * 覆盖用户点名的项：
 * 1. 首页四按钮（签到/模型/Key/接入）整块隐藏；
 * 2. 账号页渠道分组默认折叠，点组头展开；
 * 3. 设置页底部“手机端可管理…”说明隐藏；
 * 4. “更新 UI”磁贴缩到 44dp 见方；
 * 5. 顶栏重复的“设置”按钮隐藏；
 * 6. “Agent 配置”入口隐藏；
 * 7. “路由组”磁贴改名“路由”，点击直接进路由页（那里有路由模式/会话粘性）。
 */
internal object A2aPanelTweaks {

    val JS: String = """
(function () {
  if (window.__minisPanelTweaks2) return;
  window.__minisPanelTweaks2 = true;

  var style = document.createElement('style');
  style.textContent = [
    '[data-slot="bottom-navigation-item"]{flex:0 0 25% !important;max-width:25% !important;}',
    '.minis-hide{display:none !important;}',
    '.minis-group-arrow{margin-left:8px;font-size:12px;color:var(--m-text-3);}'
  ].join('');
  document.head.appendChild(style);

  function all(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); }
  function txt(e) { return (e && e.textContent ? e.textContent : '').trim(); }
  function hide(e) { if (e) e.classList.add('minis-hide'); }

  function hideQuickActions() {
    var hits = all('button,a,div').filter(function (e) {
      var t = txt(e);
      return t === '模型' || t === 'Key' || t === '接入';
    });
    if (hits.length < 3) return;
    var p = hits[0];
    for (var i = 0; i < 6 && p; i++) {
      p = p.parentElement;
      if (!p) break;
      var t = txt(p);
      if (t.indexOf('模型') >= 0 && t.indexOf('Key') >= 0 && t.indexOf('接入') >= 0) {
        hide(p);
        return;
      }
    }
  }

  function collapseAccountGroups() {
    all('[data-screen="accounts"] .m-section').forEach(function (sec) {
      if (sec.dataset.minisCollapsed) return;
      var card = sec.querySelector('[data-slot="card"]');
      if (!card) return;
      sec.dataset.minisCollapsed = '1';
      card.style.display = 'none';
      var header = sec.firstElementChild;
      if (!header) return;
      header.style.cursor = 'pointer';
      var arrow = document.createElement('span');
      arrow.className = 'minis-group-arrow';
      arrow.textContent = '▸';
      header.appendChild(arrow);
      header.addEventListener('click', function () {
        var open = card.style.display !== 'none';
        card.style.display = open ? 'none' : '';
        arrow.textContent = open ? '▸' : '▾';
      });
    });
  }

  function hideFooterText() {
    all('p,div,span').filter(function (e) {
      return e.children.length === 0 && txt(e).indexOf('手机端可管理') >= 0;
    }).forEach(hide);
  }

  function shrinkUpdateTile() {
    all('button,div,a').filter(function (e) {
      return txt(e).indexOf('UI v') >= 0 && e.children.length <= 2;
    }).forEach(function (e) {
      e.style.width = '44px';
      e.style.height = '44px';
      e.style.minWidth = '44px';
      e.style.padding = '0';
      e.style.fontSize = '9px';
      e.style.borderRadius = '12px';
      e.style.display = 'flex';
      e.style.alignItems = 'center';
      e.style.justifyContent = 'center';
    });
  }

  function hideTopSettings() {
    all('header button,[data-slot="page-header"] button').filter(function (b) {
      var l = (b.getAttribute('aria-label') || '') + (b.getAttribute('title') || '');
      return l.indexOf('设置') >= 0;
    }).forEach(hide);
  }

  function hideAgentEntry() {
    all('button,a,[role="button"],li,div').filter(function (e) {
      return txt(e) === 'Agent 配置' && e.children.length <= 2;
    }).forEach(hide);
  }

  function renameRouteTile() {
    all('button,a,[role="button"],div').filter(function (e) {
      return txt(e) === '路由组' && e.children.length <= 3;
    }).forEach(function (e) {
      e.textContent = '路由';
      e.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        location.hash = '#routing';
      }, true);
    });
  }

  function apply() {
    hideQuickActions();
    collapseAccountGroups();
    hideFooterText();
    shrinkUpdateTile();
    hideTopSettings();
    hideAgentEntry();
    renameRouteTile();
  }

  var ob = new MutationObserver(function () { apply(); });
  ob.observe(document.body, { childList: true, subtree: true });
  setInterval(apply, 1000);
  apply();
})();
""".trimIndent()
}
