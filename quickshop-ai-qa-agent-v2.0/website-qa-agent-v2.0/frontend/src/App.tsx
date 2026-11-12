import { useEffect, useState } from 'react';
import {
  ShieldCheck, Globe, Play, RefreshCw, Activity, Bug, Plus, ExternalLink,
  Image, Sparkles, Server, Lock, Gauge, Download, Search, X,
  Code2, Layers, ChevronRight, Trash2, History, Eye
} from 'lucide-react';

const API = '/api';

type Project = {
  id: string;
  name: string;
  baseUrl: string;
  allowedPathPrefix: string;
  maxPages: number;
  sourceRepoUrl?: string;
};

type Scan = {
  id: string;
  projectId?: string;
  status: string;
  message: string;
  pagesDiscovered: number;
  pagesChecked: number;
  createdAt?: string;
  finishedAt?: string | null;
  findingCount?: number;
};

type Finding = {
  id: string;
  category: string;
  severity: string;
  title: string;
  description: string;
  url: string;
  confidence: string;
  tool?: string;
  screenshot?: string | null;
};

type SonarSummary = {
  available: boolean;
  projectKey?: string;
  dashboardUrl?: string;
  ncloc?: number;
  files?: number;
  duplicatedLinesDensity?: string;
  sqaleIndexMinutes?: number;
  securityHotspots?: number;
  vulnerabilities?: number;
  bugs?: number;
  codeSmells?: number;
  totalOpenIssues?: number;
  securityIssues?: { total?: number; BLOCKER?: number; HIGH?: number; MEDIUM?: number; LOW?: number; INFO?: number };
  reliabilityIssues?: { total?: number; BLOCKER?: number; HIGH?: number; MEDIUM?: number; LOW?: number; INFO?: number };
  maintainabilityIssues?: { total?: number; BLOCKER?: number; HIGH?: number; MEDIUM?: number; LOW?: number; INFO?: number };
};

type TabType = 'overview' | 'ui' | 'ai' | 'sonar' | 'api' | 'security' | 'performance' | 'history' | 'all';
type SonarFilterType = 'ALL' | 'VULNERABILITY' | 'BUG' | 'CODE_SMELL' | 'MQR_SECURITY' | 'MQR_RELIABILITY' | 'MQR_MAINTAINABILITY';
type UiFilterType = 'ALL' | 'DEFECTS' | 'RESPONSIVE' | 'CONSOLE_NET' | 'INTERACTION' | 'A11Y_SEO';

export default function App() {
  const [activeTab, setActiveTab] = useState<TabType>('overview');
  const [projects, setProjects] = useState<Project[]>([]);
  const [selected, setSelected] = useState('');
  const [scans, setScans] = useState<Scan[]>([]);
  const [active, setActive] = useState<Scan | null>(null);
  const [findings, setFindings] = useState<Finding[]>([]);
  const [sonarSummary, setSonarSummary] = useState<SonarSummary | null>(null);

  const [name, setName] = useState('QuickShop Production');
  const [url, setUrl] = useState('https://quickshop.io.vn/');
  const [prefix, setPrefix] = useState('/');
  const [repo, setRepo] = useState('https://github.com/Leducanh30112006/HN_KS24_CNTT2_OJT.git');

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [clearPrevOnScan, setClearPrevOnScan] = useState(false);
  const [selectedScanIds, setSelectedScanIds] = useState<string[]>([]);

  // Filters & Pagination
  const [sonarFilter, setSonarFilter] = useState<SonarFilterType>('ALL');
  const [sonarLimit, setSonarLimit] = useState(100);
  const [uiFilter, setUiFilter] = useState<UiFilterType>('ALL');
  const [searchQuery, setSearchQuery] = useState('');

  // Screenshot Modal
  const [previewImage, setPreviewImage] = useState<{ src: string; title: string } | null>(null);

  async function loadSonarSummary() {
    try {
      const r = await fetch(`${API}/sonar/summary`);
      if (r.ok) {
        setSonarSummary(await r.json());
      }
    } catch { /* ignore */ }
  }

  async function load() {
    try {
      const r = await fetch(`${API}/projects`);
      if (!r.ok) throw new Error('Backend chưa sẵn sàng');
      const data = await r.json();
      setProjects(data);
      if (!selected && data[0]) {
        setSelected(data[0].id);
        await loadLatestScan(data[0].id);
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function loadLatestScan(projectId: string) {
    setSelectedScanIds([]);
    try {
      const r = await fetch(`${API}/projects/${projectId}/scans`);
      if (r.ok) {
        const scs: Scan[] = await r.json();
        setScans(scs);
        if (scs[0]) {
          setActive(scs[0]);
          const fRes = await fetch(`${API}/scans/${scs[0].id}/findings`);
          if (fRes.ok) setFindings(await fRes.json());
        } else {
          setActive(null);
          setFindings([]);
        }
      }
    } catch { /* ignore */ }
  }

  useEffect(() => {
    load();
    loadSonarSummary();
  }, []);

  useEffect(() => {
    if (selected) loadLatestScan(selected);
  }, [selected]);

  async function createProject(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const r = await fetch(`${API}/projects`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, baseUrl: url, allowedPathPrefix: prefix, maxPages: 40, sourceRepoUrl: repo })
      });
      const d = await r.json();
      if (!r.ok) throw new Error(d.error || 'Không thể tạo project');
      await load();
      setSelected(d.id);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function startScan() {
    if (!selected) return;
    if (clearPrevOnScan && scans.length > 0 && !window.confirm(`Xóa ${scans.length} bản quét trước và bắt đầu lượt quét mới?`)) {
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    setSelectedScanIds([]);
    try {
      const r = await fetch(`${API}/projects/${selected}/scans?clearPrevious=${clearPrevOnScan}`, { method: 'POST' });
      const d = await r.json();
      if (!r.ok) throw new Error(d.error || 'Không thể khởi động lượt quét');
      setActive(d);
      setFindings([]);
      const scRes = await fetch(`${API}/projects/${selected}/scans`);
      if (scRes.ok) setScans(await scRes.json());
      poll(d.id);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function selectScan(scanId: string) {
    const sc = scans.find(s => s.id === scanId);
    if (sc) setActive(sc);
    try {
      const fRes = await fetch(`${API}/scans/${scanId}/findings`);
      if (fRes.ok) setFindings(await fRes.json());
    } catch { /* ignore */ }
  }

  async function deleteSingleScan(scanId: string) {
    if (!window.confirm('Bạn có chắc muốn xóa lượt quét này cùng toàn bộ ảnh chụp và lỗi đã lưu của nó?')) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const r = await fetch(`${API}/scans/${scanId}`, { method: 'DELETE' });
      const d = await r.json();
      if (!r.ok) throw new Error(d.error || 'Không thể xóa lượt quét');
      setNotice(`Đã xóa lượt quét (${d.deletedFindings ?? 0} dữ liệu phát hiện đã được dọn dẹp).`);
      if (selected) await loadLatestScan(selected);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function clearOldScans(keepLatest: boolean) {
    if (!selected) return;
    const msg = keepLatest
      ? 'Xóa tất cả các bản quét trước và CHỈ GIỮ LẠI bản quét mới nhất?'
      : 'CẢNH BÁO: Xóa TOÀN BỘ lịch sử các lượt quét và dữ liệu phát hiện của dự án này?';
    if (!window.confirm(msg)) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const r = await fetch(`${API}/projects/${selected}/scans?keepLatest=${keepLatest}`, { method: 'DELETE' });
      const d = await r.json();
      if (!r.ok) throw new Error(d.error || 'Không thể xóa dữ liệu cũ');
      setNotice(`Đã xóa ${d.deletedScans} bản quét trước. Còn lại ${d.remainingScans} lượt quét.`);
      await loadLatestScan(selected);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function toggleScanSelection(scanId: string) {
    setSelectedScanIds(prev => prev.includes(scanId) ? prev.filter(id => id !== scanId) : [...prev, scanId]);
  }

  function selectAllPreviousScans() {
    const oldIds = scans.slice(1).map(s => s.id);
    setSelectedScanIds(prev => prev.length === oldIds.length ? [] : oldIds);
  }

  async function deleteSelectedScans() {
    if (selectedScanIds.length === 0) return;
    if (!window.confirm(`Xóa ${selectedScanIds.length} bản quét đã chọn cùng toàn bộ dữ liệu và ảnh chụp của chúng?`)) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const r = await fetch(`${API}/scans/batch-delete`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scanIds: selectedScanIds }),
      });
      const d = await r.json();
      if (!r.ok) throw new Error(d.error || 'Không thể xóa các bản quét đã chọn');
      setNotice(`Đã xóa ${d.deletedScans} bản quét đã chọn.`);
      if (selected) await loadLatestScan(selected);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function poll(id: string) {
    const tick = async () => {
      try {
        const r = await fetch(`${API}/scans/${id}`);
        const d = await r.json();
        setActive(d);
        if (['COMPLETED', 'FAILED'].includes(d.status)) {
          const f = await fetch(`${API}/scans/${id}/findings`);
          if (f.ok) setFindings(await f.json());
          if (selected) {
            const scRes = await fetch(`${API}/projects/${selected}/scans`);
            if (scRes.ok) setScans(await scRes.json());
          }
          await loadSonarSummary();
          return false;
        }
        return true;
      } catch {
        return false;
      }
    };
    let count = 0;
    while (count++ < 150 && (await tick())) await new Promise((res) => setTimeout(res, 2000));
  }

  function exportSonarIssues(format: 'json' | 'csv') {
    const sonarIssues = findings.filter(f => (f.tool === 'SONARQUBE' || f.category.startsWith('SONAR_')) && !['SONAR_OVERVIEW', 'SONAR_ERROR', 'SONAR_SCAN_EXEC'].includes(f.category));
    if (!sonarIssues.length) {
      alert('Chưa có dữ liệu SonarQube để xuất. Hãy chạy lượt quét mới để đồng bộ đầy đủ dữ liệu.');
      return;
    }

    if (format === 'json') {
      const dataStr = 'data:text/json;charset=utf-8,' + encodeURIComponent(JSON.stringify(sonarIssues, null, 2));
      const downloadAnchor = document.createElement('a');
      downloadAnchor.setAttribute('href', dataStr);
      downloadAnchor.setAttribute('download', `sonarqube-issues-${selected.slice(0, 8)}.json`);
      document.body.appendChild(downloadAnchor);
      downloadAnchor.click();
      downloadAnchor.remove();
    } else {
      let csv = '\uFEFFID,Mức độ,Phân loại,Tiêu đề,Chi tiết,Đường dẫn,Độ tin cậy\n';
      sonarIssues.forEach(i => {
        csv += `"${i.id}","${i.severity}","${i.category}","${i.title.replace(/"/g, '""')}","${i.description.replace(/"/g, '""')}","${i.url}","${i.confidence}"\n`;
      });
      const dataStr = 'data:text/csv;charset=utf-8,' + encodeURIComponent(csv);
      const downloadAnchor = document.createElement('a');
      downloadAnchor.setAttribute('href', dataStr);
      downloadAnchor.setAttribute('download', `sonarqube-issues-${selected.slice(0, 8)}.csv`);
      document.body.appendChild(downloadAnchor);
      downloadAnchor.click();
      downloadAnchor.remove();
    }
  }

  const project = projects.find(p => p.id === selected);

  // Grouped findings
  const aiFinding = findings.find(f => f.category === 'AI_ANALYSIS');
  const uiFindings = findings.filter(f =>
    f.tool === 'PLAYWRIGHT' || ['BROWSER', 'FRONTEND', 'BROWSER_INVENTORY', 'SEO', 'NETWORK', 'UI_LAYOUT', 'UI_RESPONSIVE', 'UI_MEDIA', 'UI_INTERACTION', 'UI_ACCESSIBILITY'].includes(f.category)
  );
  const sonarAllFindings = findings.filter(f => f.tool === 'SONARQUBE' || f.category.startsWith('SONAR_'));
  const sonarScanExec = sonarAllFindings.find(f => f.category === 'SONAR_SCAN_EXEC');
  const sonarIssueItems = sonarAllFindings.filter(f => !['SONAR_OVERVIEW', 'SONAR_ERROR', 'SONAR_SCAN_EXEC'].includes(f.category));
  const apiFindings = findings.filter(f => f.tool === 'REST_ASSURED' || f.category.startsWith('API_'));
  const securityFindings = findings.filter(f => f.tool === 'OWASP_ZAP' || f.category.startsWith('ZAP_') || f.category === 'SECURITY_HEADERS' || f.category === 'SECRETS');
  const perfFindings = findings.filter(f => f.tool === 'LIGHTHOUSE_K6' || f.category.startsWith('PERF_'));

  // Exact live SonarQube numbers (fallback to synced issue items if live API unavailable)
  const sonarVulnCount = sonarSummary?.available ? (sonarSummary.vulnerabilities ?? 0) : sonarIssueItems.filter(f => f.category === 'SONAR_VULNERABILITY').length;
  const sonarBugCount = sonarSummary?.available ? (sonarSummary.bugs ?? 0) : sonarIssueItems.filter(f => f.category === 'SONAR_BUG').length;
  const sonarSmellCount = sonarSummary?.available ? (sonarSummary.codeSmells ?? 0) : sonarIssueItems.filter(f => f.category === 'SONAR_CODE_SMELL').length;
  const sonarTotalOpen = sonarSummary?.available ? (sonarSummary.totalOpenIssues ?? sonarIssueItems.length) : sonarIssueItems.length;
  const mqrSecCount = sonarSummary?.available ? (sonarSummary.securityIssues?.total ?? 0) : sonarIssueItems.filter(f => f.description.includes('SECURITY')).length;
  const mqrRelCount = sonarSummary?.available ? (sonarSummary.reliabilityIssues?.total ?? 0) : sonarIssueItems.filter(f => f.description.includes('RELIABILITY')).length;
  const mqrMaintCount = sonarSummary?.available ? (sonarSummary.maintainabilityIssues?.total ?? 0) : sonarIssueItems.filter(f => f.description.includes('MAINTAINABILITY')).length;
  const sonarLoc = sonarSummary?.available ? (sonarSummary.ncloc ?? 0) : 0;
  const sonarFiles = sonarSummary?.available ? (sonarSummary.files ?? 0) : 0;
  const sonarDups = sonarSummary?.available ? (sonarSummary.duplicatedLinesDensity ?? '0') : '0';
  const sonarDebtMin = sonarSummary?.available ? (sonarSummary.sqaleIndexMinutes ?? 0) : 0;

  // Filtered Sonar findings
  const filteredSonar = sonarIssueItems.filter(f => {
    if (sonarFilter === 'VULNERABILITY' && f.category !== 'SONAR_VULNERABILITY') return false;
    if (sonarFilter === 'BUG' && f.category !== 'SONAR_BUG') return false;
    if (sonarFilter === 'CODE_SMELL' && f.category !== 'SONAR_CODE_SMELL') return false;
    if (sonarFilter === 'MQR_SECURITY' && !f.description.includes('SECURITY')) return false;
    if (sonarFilter === 'MQR_RELIABILITY' && !f.description.includes('RELIABILITY')) return false;
    if (sonarFilter === 'MQR_MAINTAINABILITY' && !f.description.includes('MAINTAINABILITY')) return false;
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      return f.title.toLowerCase().includes(q) || f.description.toLowerCase().includes(q) || (f.url && f.url.toLowerCase().includes(q));
    }
    return true;
  });

  // Filtered UI Findings
  const uiScreenshots = uiFindings.filter(f => !!f.screenshot);
  const uiDefects = uiFindings.filter(f => f.severity !== 'INFO');
  const filteredUi = uiFindings.filter(f => {
    if (uiFilter === 'DEFECTS') return f.severity !== 'INFO' && ['UI_MEDIA', 'UI_LAYOUT', 'BROWSER'].includes(f.category);
    if (uiFilter === 'RESPONSIVE') return f.category === 'UI_RESPONSIVE';
    if (uiFilter === 'CONSOLE_NET') return ['FRONTEND', 'NETWORK'].includes(f.category);
    if (uiFilter === 'INTERACTION') return f.category === 'UI_INTERACTION';
    if (uiFilter === 'A11Y_SEO') return ['UI_ACCESSIBILITY', 'SEO'].includes(f.category);
    return true;
  });

  const formatTime = (iso?: string | null) => {
    if (!iso) return '—';
    try {
      return new Date(iso).toLocaleString('vi-VN');
    } catch {
      return iso;
    }
  };

  return (
    <div className="shell">
      {/* Sidebar Navigation */}
      <aside>
        <div className="brand">
          <ShieldCheck size={26} />
          <div>
            <span>QuickShop QA</span>
            <small style={{ display: 'block', fontSize: 10, color: '#93c5fd', fontWeight: 400 }}>Multi-layer Suite</small>
          </div>
        </div>

        <div className="nav-group">LỚP KIỂM THỬ (TEST LAYERS)</div>

        <button className={`nav-btn ${activeTab === 'overview' ? 'active' : ''}`} onClick={() => setActiveTab('overview')}>
          <Activity size={17} /> <span>Tổng quan (Overview)</span>
        </button>

        <button className={`nav-btn ${activeTab === 'ui' ? 'active' : ''}`} onClick={() => setActiveTab('ui')}>
          <Image size={17} /> <span>UI Testing (Playwright)</span>
          {uiDefects.length > 0 && <span className="nav-badge">{uiDefects.length}</span>}
        </button>

        <button className={`nav-btn ${activeTab === 'ai' ? 'active' : ''}`} onClick={() => setActiveTab('ai')}>
          <Sparkles size={17} /> <span>AI Exploration</span>
        </button>

        <button className={`nav-btn ${activeTab === 'sonar' ? 'active' : ''}`} onClick={() => setActiveTab('sonar')}>
          <Code2 size={17} /> <span>SonarQube Explorer</span>
          <span className="nav-badge sonar-count">{sonarTotalOpen}</span>
        </button>

        <button className={`nav-btn ${activeTab === 'api' ? 'active' : ''}`} onClick={() => setActiveTab('api')}>
          <Server size={17} /> <span>API Testing (REST Assured)</span>
        </button>

        <button className={`nav-btn ${activeTab === 'security' ? 'active' : ''}`} onClick={() => setActiveTab('security')}>
          <Lock size={17} /> <span>Security (OWASP ZAP)</span>
        </button>

        <button className={`nav-btn ${activeTab === 'performance' ? 'active' : ''}`} onClick={() => setActiveTab('performance')}>
          <Gauge size={17} /> <span>Performance (Lighthouse)</span>
        </button>

        <div className="nav-group" style={{ marginTop: 15 }}>QUẢN LÝ & DỮ LIỆU</div>
        <button className={`nav-btn ${activeTab === 'history' ? 'active' : ''}`} onClick={() => setActiveTab('history')}>
          <History size={17} /> <span>Lịch sử lượt quét</span>
          <span className="nav-badge sonar-count">{scans.length}</span>
        </button>

        <button className={`nav-btn ${activeTab === 'all' ? 'active' : ''}`} onClick={() => setActiveTab('all')}>
          <Bug size={17} /> <span>Tất cả Findings ({findings.length})</span>
        </button>

        <a href={sonarSummary?.dashboardUrl || 'http://localhost:9000/dashboard?id=quickshop'} target="_blank" rel="noreferrer" className="nav-btn external" style={{ marginTop: 'auto' }}>
          <ExternalLink size={16} /> <span>Mở SonarQube Server</span>
        </a>
      </aside>

      {/* Main Content Area */}
      <main>
        <header>
          <div>
            <div className="eyebrow">ENTERPRISE QA AUTOMATION AGENT</div>
            <h1>
              {activeTab === 'overview' && 'Tổng quan Hệ thống Kiểm thử'}
              {activeTab === 'ui' && 'UI Testing Chuyên sâu & Ảnh chụp màn hình (Playwright)'}
              {activeTab === 'ai' && 'AI Autonomous Exploration (Stagehand + Gemini)'}
              {activeTab === 'sonar' && 'SonarQube Code Explorer (Đồng bộ 100% số liệu SonarQube)'}
              {activeTab === 'api' && 'API Testing (Spring Boot & REST Assured)'}
              {activeTab === 'security' && 'Security Testing (OWASP ZAP DAST)'}
              {activeTab === 'performance' && 'Hiệu năng & Tải đồng thời (Lighthouse + k6)'}
              {activeTab === 'history' && 'Quản lý Lượt quét & Dọn dẹp Dữ liệu cũ'}
              {activeTab === 'all' && 'Tất cả phát hiện từ các công cụ'}
            </h1>
            <p>Hợp nhất 6 lớp kiểm thử chất lượng mã nguồn và vận hành giao diện.</p>
          </div>
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
            {scans.length > 0 && (
              <select
                value={active?.id || ''}
                onChange={e => selectScan(e.target.value)}
                style={{ padding: '6px 10px', borderRadius: 6, border: '1px solid #cbd5e1', background: '#fff', fontSize: 13, fontWeight: 500, width: 'auto' }}
              >
                {scans.map((s, idx) => (
                  <option key={s.id} value={s.id}>
                    Lượt quét #{scans.length - idx} ({s.status}) - {s.createdAt ? new Date(s.createdAt).toLocaleTimeString('vi-VN') : s.id.slice(0, 8)}
                  </option>
                ))}
              </select>
            )}
            <a href={sonarSummary?.dashboardUrl || 'http://localhost:9000/dashboard?id=quickshop'} target="_blank" rel="noreferrer" className="sonar-pill">
              ⚡ SonarQube: 9000 <ExternalLink size={12} />
            </a>
            <span className="online">● System Ready</span>
          </div>
        </header>

        {error && <div className="error">{error}</div>}
        {notice && <div className="notice">{notice}</div>}

        {/* Top KPI Stats */}
        <section className="stats">
          <div className="stat clickable" onClick={() => setActiveTab('history')} title="Bấm để xem lịch sử & xóa lượt quét cũ">
            <span>Tổng số lượt quét</span>
            <strong>{scans.length} lượt</strong>
            <small>{active ? `Đang xem: ${active.status} (${active.pagesChecked} trang)` : 'Chưa có lượt quét'} · Quản lý →</small>
          </div>
          <div className="stat">
            <span>Trạng thái quét hiện tại</span>
            <strong className="small-status">{active?.status || 'IDLE'}</strong>
            <small>{active?.message || 'Chưa có lượt quét'}</small>
          </div>
          <div className="stat clickable" onClick={() => setActiveTab('sonar')} title="Số liệu trực tiếp từ SonarQube Server">
            <span>SonarQube (Chuẩn MQR & Classic)</span>
            <strong style={{ color: '#dc2626', fontSize: 18 }}>
              {mqrSecCount} Security · {mqrRelCount} Reliability
            </strong>
            <small>
              {mqrMaintCount} Maintainability | Classic: {sonarVulnCount} Vulns · {sonarBugCount} Bugs · {sonarSmellCount} Smells
            </small>
          </div>
          <div className="stat clickable" onClick={() => setActiveTab('all')}>
            <span>Tổng phát hiện (Đợt quét này)</span>
            <strong style={{ color: '#2563eb' }}>{findings.length}</strong>
            <small>Playwright ({uiFindings.length}) + Sonar ({sonarIssueItems.length}) + ZAP/API</small>
          </div>
        </section>

        {/* TAB 1: OVERVIEW */}
        {activeTab === 'overview' && (
          <>
            {aiFinding && (
              <section className="panel ai-card">
                <div className="ai-card-head">
                  <Sparkles size={20} color="#16a34a" />
                  <h3>Báo cáo Đánh giá QA Lead Hợp nhất (Gemini AI)</h3>
                </div>
                <div className="ai-content">{aiFinding.description}</div>
              </section>
            )}

            <section className="grid">
              <div className="panel">
                <div className="panel-head">
                  <div>
                    <h2>Kích hoạt đợt quét toàn diện</h2>
                    <p>Chạy đồng thời: Playwright UI đa màn hình + SonarQube + REST Assured + ZAP + Lighthouse.</p>
                  </div>
                  <Play />
                </div>
                <label>
                  Chọn dự án mục tiêu
                  <select value={selected} onChange={e => setSelected(e.target.value)}>
                    <option value="">-- Chọn website --</option>
                    {projects.map(p => <option key={p.id} value={p.id}>{p.name} ({p.baseUrl})</option>)}
                  </select>
                </label>
                {project && (
                  <div className="target">
                    <Globe size={18} />
                    <div>
                      <b>{project.name}</b>
                      <small>{project.baseUrl}{project.allowedPathPrefix}</small>
                    </div>
                    <a href={project.baseUrl} target="_blank" rel="noreferrer"><ExternalLink size={15} /></a>
                  </div>
                )}
                <div className="safe">
                  <ShieldCheck size={18} />
                  <span>
                    <b>6 Lớp kiểm thử tự động đã bật</b>
                    <small>Playwright Desktop/Mobile + Interactive Flow, AI Stagehand, REST Assured, SonarQube Full Sync, ZAP, Lighthouse.</small>
                  </span>
                </div>
                <label className="check-inline">
                  <input type="checkbox" checked={clearPrevOnScan} onChange={e => setClearPrevOnScan(e.target.checked)} />
                  <span>Tự động xóa các bản quét trước khi chạy quét mới</span>
                </label>
                <div style={{ display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
                  <button className="primary" style={{ marginTop: 0, flex: 1, minWidth: 220 }} onClick={startScan} disabled={busy || !selected}>
                    <Play size={16} /> Bắt đầu quét kiểm thử hợp nhất
                  </button>
                  <button className="ghost-btn" onClick={() => setActiveTab('history')} style={{ padding: '0 14px' }}>
                    <History size={15} /> Lịch sử ({scans.length})
                  </button>
                  {scans.length > 1 && (
                    <button className="danger-btn" onClick={() => clearOldScans(true)} disabled={busy} title="Xóa tất cả bản quét cũ, chỉ giữ bản mới nhất">
                      <Trash2 size={14} /> Xóa {scans.length - 1} bản trước
                    </button>
                  )}
                </div>
              </div>

              <div className="panel">
                <div className="panel-head">
                  <div>
                    <h2>Thêm website mục tiêu</h2>
                    <p>Cấu hình thêm ứng dụng hoặc môi trường staging mới.</p>
                  </div>
                  <Plus />
                </div>
                <form onSubmit={createProject}>
                  <label>Tên dự án<input value={name} onChange={e => setName(e.target.value)} required /></label>
                  <label>Website URL<input value={url} onChange={e => setUrl(e.target.value)} required placeholder="https://..." /></label>
                  <label>Path prefix<input value={prefix} onChange={e => setPrefix(e.target.value)} required /></label>
                  <label>Git Repository (tùy chọn)<input value={repo} onChange={e => setRepo(e.target.value)} placeholder="https://github.com/..." /></label>
                  <button disabled={busy}><Plus size={16} /> Lưu dự án</button>
                </form>
              </div>
            </section>

            {/* Quick Summary Grid of Tools */}
            <section className="tool-grid">
              <div className="tool-card" onClick={() => setActiveTab('ui')}>
                <div className="tc-header"><Image size={20} color="#2563eb" /> <b>UI Testing</b></div>
                <div className="tc-count">{uiFindings.length} kiểm tra ({uiDefects.length} lỗi)</div>
                <p>Kiểm tra nhiều trang, Desktop & Mobile Responsive, ảnh vỡ, nút bấm, thao tác tìm kiếm & chụp ảnh lỗi.</p>
                <div className="tc-link">Xem chi tiết & ảnh chụp <ChevronRight size={14} /></div>
              </div>

              <div className="tool-card" onClick={() => setActiveTab('sonar')}>
                <div className="tc-header"><Code2 size={20} color="#ea580c" /> <b>SonarQube</b></div>
                <div className="tc-count">{sonarTotalOpen} vấn đề mở</div>
                <p>MQR: {mqrSecCount} Security · {mqrRelCount} Reliability · {mqrMaintCount} Maintainability ({sonarVulnCount} Vulns, {sonarBugCount} Bugs, {sonarSmellCount} Smells).</p>
                <div className="tc-link">Xem & xuất mã lỗi <ChevronRight size={14} /></div>
              </div>

              <div className="tool-card" onClick={() => setActiveTab('api')}>
                <div className="tc-header"><Server size={20} color="#059669" /> <b>REST Assured</b></div>
                <div className="tc-count">{apiFindings.length} endpoints</div>
                <p>Kiểm tra độ trễ, HTTP status, định dạng JSON của microservices Spring Boot.</p>
                <div className="tc-link">Xem API health <ChevronRight size={14} /></div>
              </div>

              <div className="tool-card" onClick={() => setActiveTab('security')}>
                <div className="tc-header"><Lock size={20} color="#7c3aed" /> <b>OWASP ZAP</b></div>
                <div className="tc-count">{securityFindings.length} rủi ro</div>
                <p>Rà quét DAST, chống Clickjacking, CSP, Cookie bảo mật, CORS wildcard.</p>
                <div className="tc-link">Xem báo cáo bảo mật <ChevronRight size={14} /></div>
              </div>
            </section>
          </>
        )}

        {/* TAB 2: UI TESTING & SCREENSHOTS */}
        {activeTab === 'ui' && (
          <section className="panel">
            <div className="panel-head">
              <div>
                <h2>Kiểm thử Giao diện Chuyên sâu & Thư viện Ảnh (Playwright)</h2>
                <p>Tự động kiểm tra nhiều trang (Desktop 1440px & Mobile 390px), khoanh đỏ ảnh vỡ, kiểm tra tràn khung, thao tác tìm kiếm/chuyển trang, A11y và Console/Network.</p>
              </div>
              <Image />
            </div>

            <div className="sonar-metrics-bar" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
              <div className="sm-box"><span>Trang đã kiểm tra</span><strong>{active?.pagesChecked ?? 0} / {active?.pagesDiscovered ?? 0}</strong><small>SPA Multi-page</small></div>
              <div className="sm-box"><span>Ảnh chụp màn hình</span><strong>{uiScreenshots.length}</strong><small>Desktop, Mobile & Lỗi</small></div>
              <div className="sm-box red"><span>Lỗi giao diện / Tài nguyên</span><strong>{uiDefects.length}</strong><small>Ảnh vỡ, tràn khung, JS/4xx</small></div>
              <div className="sm-box green"><span>Luồng tương tác</span><strong>{uiFindings.filter(f => f.category === 'UI_INTERACTION').length}</strong><small>Search & Navigation Click</small></div>
            </div>

            <div className="filter-bar">
              <div className="filter-pills">
                <button className={`pill ${uiFilter === 'ALL' ? 'active' : ''}`} onClick={() => setUiFilter('ALL')}>
                  Tất cả ({uiFindings.length})
                </button>
                <button className={`pill ${uiFilter === 'DEFECTS' ? 'active' : ''}`} onClick={() => setUiFilter('DEFECTS')}>
                  🖼️ Ảnh vỡ & Bố cục ({uiFindings.filter(f => f.severity !== 'INFO' && ['UI_MEDIA', 'UI_LAYOUT', 'BROWSER'].includes(f.category)).length})
                </button>
                <button className={`pill ${uiFilter === 'RESPONSIVE' ? 'active' : ''}`} onClick={() => setUiFilter('RESPONSIVE')}>
                  📱 Responsive Mobile ({uiFindings.filter(f => f.category === 'UI_RESPONSIVE').length})
                </button>
                <button className={`pill ${uiFilter === 'CONSOLE_NET' ? 'active' : ''}`} onClick={() => setUiFilter('CONSOLE_NET')}>
                  ⚡ Console & Mạng 4xx/5xx ({uiFindings.filter(f => ['FRONTEND', 'NETWORK'].includes(f.category)).length})
                </button>
                <button className={`pill ${uiFilter === 'INTERACTION' ? 'active' : ''}`} onClick={() => setUiFilter('INTERACTION')}>
                  🖱️ Thao tác tương tác ({uiFindings.filter(f => f.category === 'UI_INTERACTION').length})
                </button>
                <button className={`pill ${uiFilter === 'A11Y_SEO' ? 'active' : ''}`} onClick={() => setUiFilter('A11Y_SEO')}>
                  ♿ A11y & SEO ({uiFindings.filter(f => ['UI_ACCESSIBILITY', 'SEO'].includes(f.category)).length})
                </button>
              </div>
            </div>

            {/* Visual Error Screenshots Section */}
            <div className="gallery-header">🖼️ ẢNH CHỤP MÀN HÌNH KIỂM THỬ GIAO DIỆN ({filteredUi.filter(f => f.screenshot).length} ảnh)</div>
            <div className="screenshot-grid">
              {filteredUi.filter(f => f.screenshot).length > 0 ? (
                filteredUi.filter(f => f.screenshot).map(f => (
                  <div className="screenshot-card" key={f.id} onClick={() => setPreviewImage({ src: f.screenshot!, title: f.title })}>
                    <div className="ss-thumb-wrap">
                      <img src={f.screenshot!} alt={f.title} className="ss-thumb" />
                      <div className="ss-overlay">Click để phóng to ảnh 🔍</div>
                    </div>
                    <div className="ss-info">
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <span className={`severity ${f.severity.toLowerCase()}`}>{f.severity}</span>
                        <span className="tool-badge">{f.category}</span>
                      </div>
                      <b title={f.title}>{f.title}</b>
                      <small>{f.url}</small>
                    </div>
                  </div>
                ))
              ) : (
                <div className="empty" style={{ gridColumn: '1/-1' }}>Chưa có ảnh chụp nào trong nhóm bộ lọc này.</div>
              )}
            </div>

            {/* UI Findings List */}
            <h3 style={{ marginTop: 30, marginBottom: 15 }}>Danh sách chi tiết kiểm tra giao diện ({filteredUi.length} mục):</h3>
            {filteredUi.length > 0 ? filteredUi.map(f => (
              <div className="finding" key={f.id}>
                <span className={`severity ${f.severity.toLowerCase()}`}>{f.severity}</span>
                <div style={{ flex: 1 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10 }}>
                    <b>{f.title}</b>
                    {f.screenshot && (
                      <button className="ghost-btn" onClick={() => setPreviewImage({ src: f.screenshot!, title: f.title })}>
                        <Image size={13} /> Xem ảnh chụp
                      </button>
                    )}
                  </div>
                  <p>{f.description}</p>
                  <small>{f.category} · {f.url}</small>
                </div>
              </div>
            )) : (
              <div className="empty">Không có dữ liệu kiểm tra giao diện khớp với bộ lọc.</div>
            )}
          </section>
        )}

        {/* TAB 3: AI EXPLORATION */}
        {activeTab === 'ai' && (
          <section className="panel">
            <div className="panel-head">
              <div>
                <h2>AI Autonomous Exploration (Stagehand + Gemini)</h2>
                <p>AI tự động phân tích cấu trúc DOM, rà soát các luồng mua sắm, nút bấm, và kiểm tra ảnh vỡ.</p>
              </div>
              <Sparkles color="#16a34a" />
            </div>

            <div className="ai-summary-box">
              <h3>🤖 Trí tuệ nhân tạo khám phá ứng dụng</h3>
              <p>Hệ thống tự động lập bản đồ các phần tử tương tác (Buttons, Inputs, Forms) và kiểm tra tính toàn vẹn của các tính năng thương mại điện tử.</p>
            </div>

            {findings.filter(f => f.tool === 'AI_EXPLORATION' || f.category.startsWith('AI_')).map(f => (
              <div className="finding" key={f.id}>
                <span className={`severity ${f.severity.toLowerCase()}`}>{f.severity}</span>
                <div style={{ flex: 1 }}>
                  <b>{f.title}</b>
                  <p>{f.description}</p>
                  <small>{f.category} · {f.url}</small>
                  {f.screenshot && (
                    <div style={{ marginTop: 8 }}>
                      <button className="ghost-btn" onClick={() => setPreviewImage({ src: f.screenshot!, title: f.title })}>
                        <Image size={13} /> Xem ảnh DOM crawler
                      </button>
                    </div>
                  )}
                </div>
              </div>
            ))}
          </section>
        )}

        {/* TAB 4: SONARQUBE EXPLORER */}
        {activeTab === 'sonar' && (
          <section className="panel">
            <div className="panel-head">
              <div>
                <h2>SonarQube Code Quality & Security Explorer</h2>
                <p>Đồng bộ 100% dữ liệu từ SonarQube Server — hiển thị cả chuẩn mới MQR (Security / Reliability / Maintainability) và chuẩn Classic (Vulns / Bugs / Code Smells).</p>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="ghost-btn" onClick={loadSonarSummary} title="Làm mới số liệu trực tiếp từ SonarQube">
                  <RefreshCw size={14} /> Làm mới SonarQube
                </button>
                <button className="ghost-btn" onClick={() => exportSonarIssues('csv')}>
                  <Download size={14} /> Xuất file CSV
                </button>
                <button className="ghost-btn" onClick={() => exportSonarIssues('json')}>
                  <Download size={14} /> Xuất file JSON
                </button>
              </div>
            </div>

            {/* SonarScanner Re-run Execution Banner */}
            {sonarScanExec && (
              <div className={sonarScanExec.severity === 'INFO' ? 'safe' : 'notice'} style={{ marginBottom: 14, marginTop: 0 }}>
                <Code2 size={18} />
                <span>
                  <b>{sonarScanExec.title}</b>
                  <small>{sonarScanExec.description}</small>
                </span>
              </div>
            )}

            {/* MQR Metrics matching SonarQube main UI */}
            <div style={{ fontSize: 11, fontWeight: 700, color: '#475569', marginBottom: 8 }}>
              CHỈ SỐ CHÍNH TRÊN SONARQUBE DASHBOARD (MQR SOFTWARE QUALITIES · TỔNG {sonarTotalOpen} VẤN ĐỀ MỞ)
            </div>
            <div className="sonar-metrics-bar">
              <div className="sm-box">
                <span>Lines of Code</span>
                <strong>{sonarLoc.toLocaleString('vi-VN')}</strong>
                <small>{sonarFiles} file nguồn</small>
              </div>
              <div className="sm-box red">
                <span>Security (Bảo mật)</span>
                <strong>{mqrSecCount}</strong>
                <small>Classic Vulns: {sonarVulnCount} · Blocker: {sonarSummary?.securityIssues?.BLOCKER ?? 0}</small>
              </div>
              <div className="sm-box orange">
                <span>Reliability (Độ tin cậy)</span>
                <strong>{mqrRelCount}</strong>
                <small>Classic Bugs: {sonarBugCount} · High: {sonarSummary?.reliabilityIssues?.HIGH ?? 0}</small>
              </div>
              <div className="sm-box yellow">
                <span>Maintainability (Bảo trì)</span>
                <strong>{mqrMaintCount}</strong>
                <small>Code Smells: {sonarSmellCount} · Nợ: {sonarDebtMin.toLocaleString('vi-VN')}p</small>
              </div>
              <div className="sm-box green">
                <span>Trùng lặp code</span>
                <strong>{sonarDups}%</strong>
                <small>Đã đồng bộ: {sonarIssueItems.length} / {sonarTotalOpen}</small>
              </div>
            </div>

            {sonarIssueItems.length < sonarTotalOpen && (
              <div className="notice" style={{ marginBottom: 14 }}>
                Lượt quét hiện tại mới lưu {sonarIssueItems.length}/{sonarTotalOpen} lỗi chi tiết (do thực hiện trước khi nâng cấp phân trang). Hãy nhấn <b>"Bắt đầu quét kiểm thử hợp nhất"</b> để đồng bộ đầy đủ toàn bộ {sonarTotalOpen} lỗi từ SonarQube!
              </div>
            )}

            {/* Filter and Search Controls */}
            <div className="filter-bar">
              <div className="filter-pills">
                <button className={`pill ${sonarFilter === 'ALL' ? 'active' : ''}`} onClick={() => { setSonarFilter('ALL'); setSonarLimit(100); }}>
                  Tất cả ({sonarIssueItems.length})
                </button>
                <button className={`pill ${sonarFilter === 'MQR_SECURITY' ? 'active' : ''}`} onClick={() => { setSonarFilter('MQR_SECURITY'); setSonarLimit(100); }}>
                  🔐 MQR Security ({sonarIssueItems.filter(f => f.description.includes('SECURITY')).length})
                </button>
                <button className={`pill ${sonarFilter === 'MQR_RELIABILITY' ? 'active' : ''}`} onClick={() => { setSonarFilter('MQR_RELIABILITY'); setSonarLimit(100); }}>
                  ⚙️ MQR Reliability ({sonarIssueItems.filter(f => f.description.includes('RELIABILITY')).length})
                </button>
                <button className={`pill ${sonarFilter === 'MQR_MAINTAINABILITY' ? 'active' : ''}`} onClick={() => { setSonarFilter('MQR_MAINTAINABILITY'); setSonarLimit(100); }}>
                  🛠️ MQR Maintainability ({sonarIssueItems.filter(f => f.description.includes('MAINTAINABILITY')).length})
                </button>
                <button className={`pill ${sonarFilter === 'VULNERABILITY' ? 'active' : ''}`} onClick={() => { setSonarFilter('VULNERABILITY'); setSonarLimit(100); }}>
                  🛡️ Vulnerabilities ({sonarIssueItems.filter(f => f.category === 'SONAR_VULNERABILITY').length})
                </button>
                <button className={`pill ${sonarFilter === 'BUG' ? 'active' : ''}`} onClick={() => { setSonarFilter('BUG'); setSonarLimit(100); }}>
                  🐛 Bugs ({sonarIssueItems.filter(f => f.category === 'SONAR_BUG').length})
                </button>
                <button className={`pill ${sonarFilter === 'CODE_SMELL' ? 'active' : ''}`} onClick={() => { setSonarFilter('CODE_SMELL'); setSonarLimit(100); }}>
                  🧹 Code Smells ({sonarIssueItems.filter(f => f.category === 'SONAR_CODE_SMELL').length})
                </button>
              </div>
              <div className="search-wrap">
                <Search size={15} />
                <input
                  type="text"
                  placeholder="Tìm kiếm theo tên file hoặc thông điệp lỗi..."
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                />
              </div>
            </div>

            {/* Sonar Issues List */}
            <div className="issues-container">
              {filteredSonar.length > 0 ? (
                <>
                  {filteredSonar.slice(0, sonarLimit).map(f => (
                    <div className="finding sonar-item" key={f.id}>
                      <span className={`severity ${f.severity.toLowerCase()}`}>{f.severity}</span>
                      <div style={{ flex: 1 }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 10 }}>
                          <b>{f.title}</b>
                          <span className="sonar-tag">{f.category.replace('SONAR_', '')}</span>
                        </div>
                        <p>{f.description}</p>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 6 }}>
                          <small>{f.category} · Độ tin cậy: {f.confidence}</small>
                          {f.url && (
                            <a href={f.url} target="_blank" rel="noreferrer" className="sonar-link">
                              Mở dòng code trên SonarQube <ExternalLink size={12} />
                            </a>
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                  {filteredSonar.length > sonarLimit && (
                    <div style={{ display: 'flex', gap: 10, justifyContent: 'center', marginTop: 16 }}>
                      <button className="ghost-btn" onClick={() => setSonarLimit(prev => prev + 200)}>
                        Hiển thị thêm 200 (Đang xem {sonarLimit}/{filteredSonar.length})
                      </button>
                      <button className="ghost-btn" onClick={() => setSonarLimit(filteredSonar.length)}>
                        Hiện tất cả ({filteredSonar.length})
                      </button>
                    </div>
                  )}
                </>
              ) : (
                <div className="empty">Không tìm thấy mã lỗi nào khớp với bộ lọc.</div>
              )}
            </div>
          </section>
        )}

        {/* TAB 5: API TESTING */}
        {activeTab === 'api' && (
          <section className="panel">
            <div className="panel-head">
              <div>
                <h2>API Testing & Health Matrix (REST Assured)</h2>
                <p>Kiểm tra tính sẵn sàng, mã phản hồi HTTP và độ trễ phản hồi của các microservices Spring Boot.</p>
              </div>
              <Server color="#059669" />
            </div>

            {apiFindings.map(f => (
              <div className="finding" key={f.id}>
                <span className={`severity ${f.severity.toLowerCase()}`}>{f.severity}</span>
                <div style={{ flex: 1 }}>
                  <b>{f.title}</b>
                  <p>{f.description}</p>
                  <small>{f.category} · {f.url}</small>
                </div>
              </div>
            ))}
          </section>
        )}

        {/* TAB 6: SECURITY TESTING */}
        {activeTab === 'security' && (
          <section className="panel">
            <div className="panel-head">
              <div>
                <h2>Security Testing (OWASP ZAP DAST & Secret Audit)</h2>
                <p>Kiểm tra bảo mật thụ động theo tiêu chuẩn OWASP Top 10, Header phòng thủ và cờ Cookie.</p>
              </div>
              <Lock color="#7c3aed" />
            </div>

            {securityFindings.map(f => (
              <div className="finding" key={f.id}>
                <span className={`severity ${f.severity.toLowerCase()}`}>{f.severity}</span>
                <div style={{ flex: 1 }}>
                  <b>{f.title}</b>
                  <p>{f.description}</p>
                  <small>{f.category} · {f.url}</small>
                </div>
              </div>
            ))}
          </section>
        )}

        {/* TAB 7: PERFORMANCE TESTING */}
        {activeTab === 'performance' && (
          <section className="panel">
            <div className="panel-head">
              <div>
                <h2>Hiệu năng & Kiểm thử tải (Lighthouse + k6)</h2>
                <p>Đo lường thời gian phản hồi máy chủ TTFB, Web Vitals và khả năng chịu tải đồng thời.</p>
              </div>
              <Gauge color="#ea580c" />
            </div>

            {perfFindings.map(f => (
              <div className="finding" key={f.id}>
                <span className={`severity ${f.severity.toLowerCase()}`}>{f.severity}</span>
                <div style={{ flex: 1 }}>
                  <b>{f.title}</b>
                  <p>{f.description}</p>
                  <small>{f.category} · {f.url}</small>
                </div>
              </div>
            ))}
          </section>
        )}

        {/* TAB 8: SCAN HISTORY & CLEANUP */}
        {activeTab === 'history' && (
          <section className="panel">
            <div className="panel-head">
              <div>
                <h2>Lịch sử lượt quét & Quản lý dữ liệu ({scans.length} lượt quét)</h2>
                <p>Theo dõi số lượt quét đã thực hiện, chuyển đổi xem lại kết quả từng đợt hoặc xóa dữ liệu cũ để giải phóng dung lượng.</p>
              </div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {scans.length > 1 && (
                  <button className="ghost-btn" onClick={selectAllPreviousScans} disabled={busy}>
                    {selectedScanIds.length === scans.slice(1).length && scans.length > 1 ? 'Bỏ chọn bản cũ' : `Chọn tất cả bản cũ (${scans.length - 1})`}
                  </button>
                )}
                {selectedScanIds.length > 0 && (
                  <button className="danger-btn" onClick={deleteSelectedScans} disabled={busy}>
                    <Trash2 size={14} /> Xóa đã chọn ({selectedScanIds.length})
                  </button>
                )}
                <button className="ghost-btn" onClick={() => clearOldScans(true)} disabled={busy || scans.length <= 1}>
                  <Trash2 size={14} /> Xóa các bản quét trước (Giữ mới nhất)
                </button>
                <button className="danger-btn" onClick={() => clearOldScans(false)} disabled={busy || scans.length === 0}>
                  <Trash2 size={14} /> Xóa toàn bộ lịch sử
                </button>
              </div>
            </div>

            {scans.length > 0 ? (
              <div className="history-table-wrap">
                <table className="history-table">
                  <thead>
                    <tr>
                      <th style={{ width: 36 }}>
                        <input
                          type="checkbox"
                          title="Chọn / Bỏ chọn các bản quét trước"
                          checked={scans.length > 1 && selectedScanIds.length === scans.slice(1).length}
                          disabled={scans.length <= 1}
                          onChange={selectAllPreviousScans}
                        />
                      </th>
                      <th>Lượt</th>
                      <th>Trạng thái</th>
                      <th>Thời gian tạo</th>
                      <th>Hoàn tất</th>
                      <th>Trang quét</th>
                      <th>Số phát hiện</th>
                      <th>Thao tác</th>
                    </tr>
                  </thead>
                  <tbody>
                    {scans.map((s, idx) => {
                      const isCurrent = active?.id === s.id;
                      const isChecked = selectedScanIds.includes(s.id);
                      return (
                        <tr key={s.id} className={isCurrent ? 'current-row' : ''}>
                          <td>
                            <input
                              type="checkbox"
                              checked={isChecked}
                              onChange={() => toggleScanSelection(s.id)}
                              title={`Chọn lượt #${scans.length - idx}`}
                            />
                          </td>
                          <td><b>#{scans.length - idx}</b> {idx === 0 && <span className="sonar-tag">Mới nhất</span>}</td>
                          <td>
                            <span className={`status-pill ${s.status.toLowerCase()}`}>{s.status}</span>
                          </td>
                          <td>{formatTime(s.createdAt)}</td>
                          <td>{formatTime(s.finishedAt)}</td>
                          <td>{s.pagesChecked} / {s.pagesDiscovered} trang</td>
                          <td><b>{s.findingCount ?? '—'}</b></td>
                          <td>
                            <div style={{ display: 'flex', gap: 6 }}>
                              <button className="ghost-btn" onClick={() => { selectScan(s.id); setActiveTab('overview'); }}>
                                <Eye size={13} /> {isCurrent ? 'Đang xem' : 'Xem'}
                              </button>
                              <button className="danger-btn" onClick={() => deleteSingleScan(s.id)} disabled={busy}>
                                <Trash2 size={13} /> Xóa
                              </button>
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="empty">Chưa có lượt quét nào được lưu cho dự án này. Hãy nhấn "Bắt đầu quét kiểm thử hợp nhất" ở mục Tổng quan!</div>
            )}
          </section>
        )}

        {/* TAB 9: ALL FINDINGS */}
        {activeTab === 'all' && (
          <section className="panel">
            <div className="panel-head">
              <div>
                <h2>Tất cả phát hiện từ các công cụ ({findings.length})</h2>
                <p>Danh sách toàn diện gom chung từ Playwright, SonarQube, REST Assured, ZAP và Lighthouse.</p>
              </div>
              <Layers />
            </div>

            {findings.slice(0, 250).map(f => (
              <div className="finding" key={f.id}>
                <span className={`severity ${f.severity.toLowerCase()}`}>{f.severity}</span>
                <div style={{ flex: 1 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <b>{f.title}</b>
                    {f.tool && <span className="tool-badge">{f.tool}</span>}
                  </div>
                  <p>{f.description}</p>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <small>{f.category} · {f.url}</small>
                    {f.screenshot && (
                      <button className="ghost-btn" onClick={() => setPreviewImage({ src: f.screenshot!, title: f.title })}>
                        <Image size={13} /> Xem ảnh lỗi
                      </button>
                    )}
                  </div>
                </div>
              </div>
            ))}
            {findings.length > 250 && (
              <div className="empty">Hiển thị 250 phát hiện mới nhất. Để lọc toàn bộ {sonarIssueItems.length} lỗi mã nguồn, hãy mở tab SonarQube Explorer.</div>
            )}
          </section>
        )}

        <footer>QuickShop AI QA Agent v2.0 · Hợp nhất: Playwright + Stagehand + REST Assured + OWASP ZAP + Lighthouse + SonarQube.</footer>
      </main>

      {/* Screenshot Preview Modal */}
      {previewImage && (
        <div className="modal-backdrop" onClick={() => setPreviewImage(null)}>
          <div className="modal-content" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <b>{previewImage.title}</b>
              <button className="close-btn" onClick={() => setPreviewImage(null)}><X size={18} /></button>
            </div>
            <div className="modal-body">
              <img src={previewImage.src} alt={previewImage.title} className="modal-img" />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
