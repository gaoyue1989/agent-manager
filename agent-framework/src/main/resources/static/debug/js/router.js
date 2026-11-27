import { icon } from './icons.js';

const COLLAPSE_KEY = 'debug-sidebar-collapsed';

export class Router {
  constructor(routes) {
    this.routes = routes;
    this.currentModule = null;
    this.container = document.getElementById('module-content');
    this.sidebar = document.getElementById('sidebar');
    this.backdrop = document.getElementById('sidebarBackdrop');

    this.initSidebarControls();
    window.addEventListener('hashchange', () => this.resolve());
  }

  initSidebarControls() {
    if (!this.sidebar) return;
    let collapsed = false;
    try { collapsed = localStorage.getItem(COLLAPSE_KEY) === '1'; } catch (e) {}
    if (collapsed) this.sidebar.classList.add('collapsed');

    const toggleBtn = document.getElementById('sidebarToggle');
    if (toggleBtn) {
      toggleBtn.addEventListener('click', () => {
        const isCollapsed = this.sidebar.classList.toggle('collapsed');
        try { localStorage.setItem(COLLAPSE_KEY, isCollapsed ? '1' : '0'); } catch (e) {}
      });
    }

    const mobileBtn = document.getElementById('mobileMenuBtn');
    if (mobileBtn) {
      mobileBtn.addEventListener('click', () => {
        const open = this.sidebar.classList.toggle('mobile-open');
        if (this.backdrop) this.backdrop.classList.toggle('open', open);
      });
    }

    if (this.backdrop) {
      this.backdrop.addEventListener('click', () => this.closeMobileNav());
    }
  }

  closeMobileNav() {
    if (this.sidebar) this.sidebar.classList.remove('mobile-open');
    if (this.backdrop) this.backdrop.classList.remove('open');
  }

  renderNav() {
    if (!this.sidebar) return;
    let html = '';
    let lastGroup = null;
    const current = this.currentHash();
    for (const [hash, route] of Object.entries(this.routes)) {
      if (route.group && route.group !== lastGroup) {
        html += '<div class="sidebar-group-label">' + route.group + '</div>';
        lastGroup = route.group;
      }
      const active = current === hash;
      html += '<div class="nav-item' + (active ? ' active' : '') + '" data-hash="' + hash + '" data-title="' +
        route.title + '"><span class="icon">' + icon(route.icon, 18) + '</span><span class="label">' +
        route.title + '</span></div>';
    }
    this.sidebar.innerHTML = html;
    this.sidebar.querySelectorAll('.nav-item').forEach((el) => {
      el.addEventListener('click', () => {
        this.closeMobileNav();
        window.location.hash = el.dataset.hash;
      });
    });
  }

  currentHash() {
    const hash = window.location.hash || '#/';
    return this.routes[hash] ? hash : '#/';
  }

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
        agui: window.App.agui,
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
