/* ===== Hash 路由管理 ===== */

export class Router {
  constructor(routes) {
    this.routes = routes;
    this.currentModule = null;
    this.container = document.getElementById('module-content');
    this.sidebar = document.getElementById('sidebar');

    window.addEventListener('hashchange', () => this.resolve());
  }

  /** 渲染侧边栏导航 */
  renderNav() {
    let html = '<div class="nav-title">Modules</div>';
    for (const [hash, route] of Object.entries(this.routes)) {
      const active = this.currentHash() === hash;
      html += '<div class="nav-item' + (active ? ' active' : '') + '" data-hash="' + hash + '">' +
        '<span class="icon">' + route.icon + '</span>' +
        '<span>' + route.title + '</span>' +
        '</div>';
    }
    this.sidebar.innerHTML = html;
    this.sidebar.querySelectorAll('.nav-item').forEach((el) => {
      el.addEventListener('click', () => {
        window.location.hash = el.dataset.hash;
      });
    });
  }

  currentHash() {
    const hash = window.location.hash || '#/';
    return this.routes[hash] ? hash : '#/';
  }

  /** 切换模块 */
  resolve() {
    this.renderNav();
    const route = this.routes[this.currentHash()];

    if (this.currentModule && typeof this.currentModule.unmount === 'function') {
      try { this.currentModule.unmount(); } catch (e) { console.warn('unmount failed:', e); }
    }

    this.container.innerHTML = '<div class="module-loading">Loading ' + route.title + '...</div>';

    try {
      const module = route.module;
      module.mount(this.container, {
        api: window.App.api,
        state: window.App.state,
        utils: window.App.utils,
        modal: window.App.modal
      });
      this.currentModule = module;
    } catch (e) {
      console.error('module mount failed:', e);
      this.container.innerHTML = '<div class="empty error-text">Module load failed: ' + e.message + '</div>';
    }
  }
}
