const fs = require('fs');
const MarkdownIt = require('markdown-it');
const anchor = require('markdown-it-anchor');

// ── Custom fence renderer ──────────────────────────────────────────
const defaultFence = MarkdownIt().renderer.rules.fence;

const md = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: true,
  breaks: false,
});

// markdown-it-anchor generates heading IDs that match our sidebar links
// slugify: "1. 项目概述" → "1-项目概述"  (dots→dashes, spaces removed)
function slugifyHeading(s) {
  return s.replace(/\./g, '-').replace(/\s+/g, '');
}
md.use(anchor, {
  level: [2, 3],
  slugify: slugifyHeading,
  permalink: false,
});

// Custom fence: mermaid → pre.mermaid  |  bare (no lang) → pre.ascii-art  |  others → normal
const origFence = md.renderer.rules.fence;
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx];
  const info = token.info ? token.info.trim() : '';
  const content = token.content;

  if (info === 'mermaid') {
    // Escape & first (avoid double-escaping already-valid entities), then escape
    // < that looks like an HTML tag start to prevent the browser from creating DOM
    // elements from patterns like <T>, <Query>, etc.
    // For flowchart/graph types, convert <br/> to literal newlines to avoid
    // mermaid 11.x parser issues with <br> DOM elements. Sequence/state diagrams
    // keep <br/> since their Note blocks require single-line syntax.
    let escaped = content
      .replace(/&(?!amp;|lt;|gt;|quot;|#\d+;|#x[0-9a-fA-F]+;)/g, '&amp;');
    const firstLine = content.trim().split('\n')[0].toLowerCase();
    const isFlowchart = firstLine.startsWith('graph ') || firstLine.startsWith('flowchart ');
    if (isFlowchart) {
      escaped = escaped.replace(/<br\s*\/?>/gi, '\n');
    }
    escaped = escaped.replace(/<(?!br\b|\/br>)([a-zA-Z])/g, '&lt;$1');
    return '<pre class="mermaid">\n' + escaped + '\n</pre>\n';
  }

  if (!info) {
    // Bare code block → ASCII art diagram
    return '<pre class="ascii-art"><code>' + md.utils.escapeHtml(content) + '</code></pre>\n';
  }

  // Normal code block with language
  return '<pre><code class="language-' + md.utils.escapeHtml(info) + '">' +
    md.utils.escapeHtml(content) + '</code></pre>\n';
};

// ── Read & convert ─────────────────────────────────────────────────
const mdContent = fs.readFileSync(
  'docs/architecture/architecture_2026-04-30-20.md',
  'utf-8'
);

let bodyHtml = md.render(mdContent);

// ── Sidebar ─────────────────────────────────────────────────────────
const sidebarItems = [
  ['#1-项目概述', '1. 项目概述'],
  ['#2-技术栈总览', '2. 技术栈总览'],
  ['#3-系统整体架构', '3. 系统整体架构'],
  ['#4-前端完整架构', '4. 前端完整架构'],
  ['#5-后端完整架构', '5. 后端完整架构'],
  ['#6-核心查询链路完整时序', '6. 核心查询链路时序'],
  ['#7-RAG向量检索架构', '7. RAG 向量检索'],
  ['#8-元数据发布生命周期与SQL校验', '8. 元数据发布与SQL校验'],
  ['#9-权限架构', '9. 权限架构'],
  ['#10-多数据源架构', '10. 多数据源架构'],
  ['#11-安全防护体系', '11. 安全防护体系'],
  ['#12-元数据库Schema', '12. 元数据库 Schema'],
  ['#13-API接口清单', '13. API 接口清单'],
  ['#14-Prompt模板体系', '14. Prompt 模板体系'],
  ['#15-反模式警示', '15. 反模式警示'],
  ['#16-里程碑历史', '16. 里程碑历史'],
  ['#17-技术债与待完成项', '17. 技术债与待完成项'],
];

const sidebarHtml = sidebarItems
  .map(([href, label]) => `    <li><a href="${href}">${label}</a></li>`)
  .join('\n');

// ── Full HTML ───────────────────────────────────────────────────────
const fullHtml = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Text2Report 系统架构文档 — 2026-05-11</title>
<script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>
<style>
  :root {
    --sidebar-width: 280px;
    --nav-bg: #1a1a2e;
    --nav-text: #c8c8d4;
    --nav-hover: #16213e;
    --nav-active: #0f3460;
    --accent: #e94560;
    --content-bg: #fafafa;
    --card-bg: #ffffff;
    --border: #e8e8e8;
    --text: #333;
    --text-secondary: #666;
    --code-bg: #f5f5f5;
    --table-stripe: #f9f9f9;
    --warning-bg: #fff2f0;
    --warning-border: #ff4d4f;
    --success-bg: #f6ffed;
    --success-border: #52c41a;
    --ascii-bg: #f0f4f8;
    --ascii-border: #c0c8d4;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
    background: var(--content-bg); color: var(--text); line-height: 1.7;
  }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }

  /* ── Sidebar ── */
  nav#sidebar {
    position: fixed; left: 0; top: 0; bottom: 0;
    width: var(--sidebar-width); background: var(--nav-bg);
    color: var(--nav-text); overflow-y: auto; z-index: 100;
    padding: 20px 0; box-shadow: 2px 0 8px rgba(0,0,0,.15);
  }
  nav#sidebar h2 {
    font-size: 15px; padding: 0 20px 16px; border-bottom: 1px solid rgba(255,255,255,.1);
    margin-bottom: 12px; color: #fff; letter-spacing: 0.5px;
  }
  nav#sidebar ul { list-style: none; }
  nav#sidebar ul li a {
    display: block; padding: 7px 20px; color: var(--nav-text);
    font-size: 13px; transition: all .15s; border-left: 3px solid transparent;
  }
  nav#sidebar ul li a:hover {
    background: var(--nav-hover); border-left-color: var(--accent); text-decoration: none; color: #fff;
  }
  nav#sidebar ul li a.active {
    background: var(--nav-active); border-left-color: var(--accent); color: #fff; font-weight: 600;
  }

  /* ── Main Content ── */
  main#content {
    margin-left: var(--sidebar-width); padding: 40px 48px; max-width: 1120px;
  }
  h1 { font-size: 2em; margin-bottom: 8px; color: #1a1a2e; scroll-margin-top: 20px; }
  h2 { font-size: 1.45em; margin: 40px 0 16px; padding-bottom: 8px; border-bottom: 2px solid var(--border); color: #1a1a2e; scroll-margin-top: 20px; }
  h3 { font-size: 1.18em; margin: 28px 0 12px; color: #333; scroll-margin-top: 20px; }
  h4 { font-size: 1.05em; margin: 20px 0 8px; color: #555; scroll-margin-top: 20px; }
  p { margin: 10px 0; }
  ul, ol { margin: 8px 0 8px 24px; }
  li { margin: 4px 0; }
  blockquote {
    border-left: 4px solid var(--accent); margin: 16px 0; padding: 12px 20px;
    background: var(--card-bg); border-radius: 0 6px 6px 0; color: var(--text-secondary);
  }
  blockquote p { margin: 4px 0; }
  hr { border: none; border-top: 1px solid var(--border); margin: 32px 0; }

  /* ── Tables ── */
  table { width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 14px; }
  th, td { padding: 10px 14px; text-align: left; border: 1px solid var(--border); }
  th { background: var(--nav-bg); color: #fff; font-weight: 600; }
  tr:nth-child(even) td { background: var(--table-stripe); }
  tr:hover td { background: #e6f7ff; }

  /* ── Inline Code ── */
  :not(pre) > code {
    background: var(--code-bg); padding: 2px 6px; border-radius: 3px;
    font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace; font-size: 0.9em;
    color: #d63384; border: 1px solid #e8e8e8;
  }

  /* ── Normal Code Blocks ── */
  pre:not(.mermaid):not(.ascii-art) {
    background: #1e1e1e; color: #d4d4d4; padding: 16px 20px; border-radius: 6px;
    overflow-x: auto; margin: 16px 0; font-size: 13px; line-height: 1.6;
  }
  pre:not(.mermaid):not(.ascii-art) code {
    background: none; padding: 0; color: inherit; font-size: inherit; border: none;
  }

  /* ── Mermaid Diagrams ── */
  pre.mermaid {
    background: #fff; padding: 20px; border: 1px solid var(--border); border-radius: 8px;
    margin: 20px 0; overflow: auto; max-height: 600px; position: relative;
    cursor: zoom-in; transition: all 0.3s ease;
    box-shadow: 0 1px 4px rgba(0,0,0,0.06);
  }
  pre.mermaid:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.10); }
  pre.mermaid svg { max-width: 100%; height: auto; }

  /* Zoom overlay for mermaid */
  .mermaid-zoom-overlay {
    display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.75);
    z-index: 9999; overflow: hidden;
  }
  .mermaid-zoom-overlay.active { display: flex; flex-direction: column; }

  /* Zoom toolbar */
  .zoom-toolbar {
    position: fixed; top: 16px; left: 50%; transform: translateX(-50%);
    z-index: 10001; display: flex; gap: 6px; align-items: center;
    background: rgba(26,26,46,0.92); padding: 8px 14px; border-radius: 8px;
    box-shadow: 0 4px 16px rgba(0,0,0,0.35);
    user-select: none; -webkit-user-select: none;
  }
  .zoom-toolbar button {
    width: 34px; height: 34px; border: 1px solid rgba(255,255,255,0.18);
    border-radius: 6px; background: transparent; color: #fff; cursor: pointer;
    font-size: 18px; display: flex; align-items: center; justify-content: center;
    transition: background 0.15s; line-height: 1;
  }
  .zoom-toolbar button:hover { background: rgba(255,255,255,0.15); }
  .zoom-toolbar button:active { background: rgba(255,255,255,0.25); }
  .zoom-toolbar .zoom-label {
    color: #fff; font-size: 13px; min-width: 48px; text-align: center;
    font-variant-numeric: tabular-nums; font-family: 'SF Mono', 'Consolas', monospace;
  }
  .zoom-toolbar .zoom-sep {
    width: 1px; height: 20px; background: rgba(255,255,255,0.18); margin: 0 4px;
  }

  .mermaid-zoom-overlay .zoomed-svg-container {
    width: 100%; height: 100%; overflow: hidden;
    cursor: grab; display: flex; align-items: center; justify-content: center;
  }
  .mermaid-zoom-overlay .zoomed-svg-container.grabbing { cursor: grabbing; }
  .mermaid-zoom-overlay .zoomed-svg-container svg {
    background: #fff; border-radius: 8px; padding: 20px;
    box-shadow: 0 8px 40px rgba(0,0,0,0.3);
    max-width: none; max-height: none;
    transform-origin: 0 0;
  }

  .mermaid-zoom-hint {
    position: absolute; top: 8px; right: 12px;
    font-size: 11px; color: #999; pointer-events: none;
    opacity: 0; transition: opacity 0.2s;
  }
  pre.mermaid:hover .mermaid-zoom-hint { opacity: 1; }

  /* ── ASCII Art Blocks ── */
  pre.ascii-art {
    background: var(--ascii-bg); color: #2d3748; padding: 20px 24px;
    border: 1px solid var(--ascii-border); border-radius: 8px;
    overflow-x: auto; margin: 16px 0; font-size: 12px; line-height: 1.45;
    font-family: 'SF Mono', 'Fira Code', 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace;
    box-shadow: inset 0 1px 3px rgba(0,0,0,0.04);
    white-space: pre;
    letter-spacing: 0;
  }
  pre.ascii-art code {
    background: none; padding: 0; color: inherit; font-size: inherit; border: none;
    font-family: inherit; white-space: pre; letter-spacing: 0;
  }

  /* ── Cards ── */
  .card { background: var(--card-bg); border: 1px solid var(--border); border-radius: 6px; padding: 20px 24px; margin: 16px 0; }

  /* ── Warning / Success ── */
  .warning {
    background: var(--warning-bg); border: 1px solid var(--warning-border);
    border-radius: 6px; padding: 12px 16px; margin: 16px 0; font-size: 14px;
  }
  .success {
    background: var(--success-bg); border: 1px solid var(--success-border);
    border-radius: 6px; padding: 12px 16px; margin: 16px 0; font-size: 14px;
  }

  /* ── Badges ── */
  .badge {
    display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 12px; font-weight: 600;
  }
  .badge-green { background: #f6ffed; color: #52c41a; border: 1px solid #b7eb8f; }
  .badge-yellow { background: #fffbe6; color: #faad14; border: 1px solid #ffe58f; }
  .badge-red { background: #fff2f0; color: #ff4d4f; border: 1px solid #ffa39e; }

  /* ── Back to Top ── */
  #back-to-top {
    display: none; position: fixed; bottom: 32px; right: 32px; z-index: 200;
    width: 40px; height: 40px; border-radius: 50%; background: var(--nav-bg);
    color: #fff; border: none; cursor: pointer; font-size: 18px;
    box-shadow: 0 2px 8px rgba(0,0,0,0.2); transition: all 0.2s;
  }
  #back-to-top:hover { background: var(--accent); transform: translateY(-2px); }

  /* ── Responsive ── */
  @media (max-width: 768px) {
    nav#sidebar { width: 100%; height: auto; position: relative; }
    nav#sidebar ul { display: flex; flex-wrap: wrap; padding: 8px; }
    nav#sidebar ul li a { padding: 6px 12px; font-size: 11px; border-left: none; border-bottom: 2px solid transparent; }
    main#content { margin-left: 0; padding: 20px; }
    pre.ascii-art { font-size: 10px; padding: 12px; }
  }
</style>
</head>
<body>

<!-- Sidebar Navigation -->
<nav id="sidebar">
  <h2>Text2Report 架构文档</h2>
  <ul>
${sidebarHtml}
  </ul>
</nav>

<!-- Main Content -->
<main id="content">

${bodyHtml}

<p style="color: #999; font-size: 13px; text-align: center; margin-top: 48px;">
  <strong>Text2Report 系统架构文档 v2.0</strong><br>
  生成时间: 2026-05-11 CST | 代码版本: master (commit: fc1fb50) | 后端测试: 30 test files 通过<br>
  文件路径: docs/architecture/architecture_2026-04-30-20.html
</p>

</main>

<!-- Back to Top Button -->
<button id="back-to-top" title="Back to top" aria-label="Back to top">↑</button>

<!-- Mermaid Zoom Overlay -->
<div class="mermaid-zoom-overlay" id="mermaid-overlay">
  <div class="zoom-toolbar" id="zoom-toolbar" style="display:none">
    <button id="zoom-out-btn" title="缩小 (Ctrl+-)">−</button>
    <span class="zoom-label" id="zoom-label">100%</span>
    <button id="zoom-in-btn" title="放大 (Ctrl+=)">+</button>
    <span class="zoom-sep"></span>
    <button id="zoom-reset-btn" title="复位 (Ctrl+0)">↺</button>
    <span class="zoom-sep"></span>
    <button id="zoom-close-btn" title="关闭 (Esc)">✕</button>
  </div>
  <div class="zoomed-svg-container" id="mermaid-zoomed"></div>
</div>

<script>
  // ── Initialize Mermaid ──
  mermaid.initialize({
    startOnLoad: true,
    theme: 'default',
    securityLevel: 'loose',
    flowchart: { useMaxWidth: true, htmlLabels: true, curve: 'basis' },
    sequence: { useMaxWidth: true, showSequenceNumbers: true }
  });

  // ── Sidebar Scroll Spy ──
  (function() {
    const sidebarLinks = document.querySelectorAll('nav#sidebar ul li a');
    const headings = [];
    sidebarLinks.forEach(link => {
      const id = link.getAttribute('href').replace('#', '');
      const el = document.getElementById(id);
      if (el) headings.push({ el, link });
    });

    function onScroll() {
      let current = headings[0];
      const scrollY = window.scrollY + 80;
      for (const h of headings) {
        if (h.el.offsetTop <= scrollY) current = h;
      }
      sidebarLinks.forEach(l => l.classList.remove('active'));
      if (current) current.link.classList.add('active');

      // Back to top
      document.getElementById('back-to-top').style.display = window.scrollY > 400 ? 'block' : 'none';
    }

    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
  })();

  // ── Back to Top Button ──
  document.getElementById('back-to-top').addEventListener('click', () => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });

  // ── Mermaid Zoom/Pan ──
  (function() {
    const overlay = document.getElementById('mermaid-overlay');
    const zoomedContainer = document.getElementById('mermaid-zoomed');
    const toolbar = document.getElementById('zoom-toolbar');
    const zoomLabel = document.getElementById('zoom-label');
    const zoomOutBtn = document.getElementById('zoom-out-btn');
    const zoomInBtn = document.getElementById('zoom-in-btn');
    const zoomResetBtn = document.getElementById('zoom-reset-btn');
    const zoomCloseBtn = document.getElementById('zoom-close-btn');

    // Pan state
    let panX = 0, panY = 0;
    let scale = 1;
    let isDragging = false;
    let dragStartX = 0, dragStartY = 0;
    let dragStartPanX = 0, dragStartPanY = 0;

    function updateTransform(svg) {
      svg.style.transform = 'translate(' + panX + 'px, ' + panY + 'px) scale(' + scale + ')';
    }

    function updateZoomLabel() {
      zoomLabel.textContent = Math.round(scale * 100) + '%';
    }

    function resetView(svg) {
      panX = 0; panY = 0;
      scale = 1;
      updateTransform(svg);
      updateZoomLabel();
    }

    function closeOverlay() {
      overlay.classList.remove('active');
      document.body.style.overflow = '';
      toolbar.style.display = 'none';
      zoomedContainer.innerHTML = '';
    }

    // Wait for mermaid to finish rendering
    setTimeout(() => {
      document.querySelectorAll('pre.mermaid').forEach(pre => {
        // Add zoom hint
        const hint = document.createElement('span');
        hint.className = 'mermaid-zoom-hint';
        hint.textContent = '🔍 双击放大 (可拖拽/滚轮缩放)';
        pre.style.position = 'relative';
        pre.appendChild(hint);

        // Double-click to zoom
        pre.addEventListener('dblclick', (e) => {
          const svg = pre.querySelector('svg');
          if (!svg) return;
          zoomedContainer.innerHTML = '';
          const clone = svg.cloneNode(true);
          clone.removeAttribute('width');
          clone.removeAttribute('height');
          zoomedContainer.appendChild(clone);
          resetView(clone);
          overlay.classList.add('active');
          toolbar.style.display = 'flex';
          document.body.style.overflow = 'hidden';
        });
      });
    }, 1200);

    // ── Pan: mouse drag ──
    zoomedContainer.addEventListener('mousedown', (e) => {
      if (e.target.tagName === 'BUTTON') return;
      isDragging = true;
      dragStartX = e.clientX;
      dragStartY = e.clientY;
      dragStartPanX = panX;
      dragStartPanY = panY;
      zoomedContainer.classList.add('grabbing');
      e.preventDefault();
    });

    window.addEventListener('mousemove', (e) => {
      if (!isDragging) return;
      const svg = zoomedContainer.querySelector('svg');
      if (!svg) return;
      panX = dragStartPanX + (e.clientX - dragStartX);
      panY = dragStartPanY + (e.clientY - dragStartY);
      updateTransform(svg);
    });

    window.addEventListener('mouseup', () => {
      if (isDragging) {
        isDragging = false;
        zoomedContainer.classList.remove('grabbing');
      }
    });

    // ── Zoom: mouse wheel (cursor-centered) ──
    zoomedContainer.addEventListener('wheel', (e) => {
      const svg = zoomedContainer.querySelector('svg');
      if (!svg) return;
      e.preventDefault();

      const rect = svg.getBoundingClientRect();
      const mx = e.clientX - rect.left;
      const my = e.clientY - rect.top;

      const oldScale = scale;
      const newScale = e.deltaY < 0 ? scale * 1.12 : scale * 0.88;
      scale = Math.max(0.15, Math.min(8, newScale));

      // Zoom toward cursor position
      const scaleRatio = scale / oldScale;
      panX = mx - scaleRatio * (mx - panX);
      panY = my - scaleRatio * (my - panY);

      updateTransform(svg);
      updateZoomLabel();
    }, { passive: false });

    // ── Toolbar buttons ──
    zoomInBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const svg = zoomedContainer.querySelector('svg');
      if (!svg) return;
      scale = Math.min(8, scale * 1.2);
      updateTransform(svg);
      updateZoomLabel();
    });

    zoomOutBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const svg = zoomedContainer.querySelector('svg');
      if (!svg) return;
      scale = Math.max(0.15, scale * 0.8333);
      updateTransform(svg);
      updateZoomLabel();
    });

    zoomResetBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const svg = zoomedContainer.querySelector('svg');
      if (!svg) return;
      resetView(svg);
    });

    zoomCloseBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      closeOverlay();
    });

    // ── Keyboard shortcuts ──
    document.addEventListener('keydown', (e) => {
      if (!overlay.classList.contains('active')) return;
      const svg = zoomedContainer.querySelector('svg');
      if (!svg) return;
      switch (e.key) {
        case 'Escape':
          closeOverlay();
          break;
        case '+':
        case '=':
          e.preventDefault();
          scale = Math.min(8, scale * 1.2);
          updateTransform(svg);
          updateZoomLabel();
          break;
        case '-':
          e.preventDefault();
          scale = Math.max(0.15, scale * 0.8333);
          updateTransform(svg);
          updateZoomLabel();
          break;
        case '0':
          if (e.ctrlKey || e.metaKey) {
            e.preventDefault();
            resetView(svg);
          }
          break;
        case 'ArrowUp':
          e.preventDefault();
          panY -= 40 / scale;
          updateTransform(svg);
          break;
        case 'ArrowDown':
          e.preventDefault();
          panY += 40 / scale;
          updateTransform(svg);
          break;
        case 'ArrowLeft':
          e.preventDefault();
          panX -= 40 / scale;
          updateTransform(svg);
          break;
        case 'ArrowRight':
          e.preventDefault();
          panX += 40 / scale;
          updateTransform(svg);
          break;
      }
    });

    // Click overlay background to close
    overlay.addEventListener('click', (e) => {
      if (e.target === overlay) {
        closeOverlay();
      }
    });
  })();

  // ── Smooth scroll for sidebar links ──
  document.querySelectorAll('nav#sidebar a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function(e) {
      e.preventDefault();
      const id = this.getAttribute('href').replace('#', '');
      const target = document.getElementById(id);
      if (target) {
        target.scrollIntoView({ behavior: 'smooth', block: 'start' });
        history.pushState(null, null, '#' + id);
      }
    });
  });
</script>

</body>
</html>
`;

fs.writeFileSync(
  'docs/architecture/architecture_2026-04-30-20.html',
  fullHtml,
  'utf-8'
);

console.log('HTML generated successfully!');
console.log('Body size:', bodyHtml.length, 'chars');
console.log('Total size:', fullHtml.length, 'chars');

// Verify heading IDs are present
const headingMatches = bodyHtml.match(/<h[234]\s+id="/g);
console.log('Headings with IDs:', headingMatches ? headingMatches.length : 0);
