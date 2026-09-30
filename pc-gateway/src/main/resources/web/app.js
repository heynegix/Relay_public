(() => {
  const $ = (id) => document.getElementById(id);
  const state = { requests: [], selectedId: null, filter: "active", timer: null, soundTimer: null, notifiedWarning: false, staff: null, regionName: "Configured region", locale: "en", timezoneId: "UTC", mapEnabled: false };
  const initialMapView = { latitude: 0, longitude: 0, zoom: 2 };
  const mapBounds = { south: -1, north: 1, west: -1, east: 1 };
  const mapLimits = { minZoom: 0, maxNativeZoom: 2, maxZoom: 5 };
  const mapViews = {
    rescueMap: { ...initialMapView },
    fullMap: { ...initialMapView },
  };

  function nodeId() { return state.staff?.username || ""; }
  function authHeaders(json = false) {
    const headers = {};
    if (json) headers["Content-Type"] = "application/json";
    return headers;
  }
  function escapeHtml(value) {
    return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
  }
  async function api(path, options = {}) {
    const response = await fetch(path, options);
    const text = await response.text();
    let body = text;
    try { body = text ? JSON.parse(text) : null; } catch (_) { /* plain text */ }
    if (!response.ok) {
      const error = new Error(typeof body === "object" ? JSON.stringify(body) : body || response.statusText);
      error.status = response.status;
      throw error;
    }
    return body;
  }
  function fmtTime(value) { return value ? new Date(value).toLocaleString(state.locale || "en", { hour12: false, timeZone: state.timezoneId || "UTC" }) : "不明"; }
  function statusLabel(value) {
    return ({ UNCONFIRMED: "未確認", CONFIRMED: "確認済み", PREPARING: "対応準備中", RESCUE_REQUESTED: "対応要請を記録（外部連携未確認）", RESPONDING: "対応中", COMPLETED: "完了", UNABLE: "対応不可", DUPLICATE: "重複" })[value] || value;
  }
  function profileLabel(profile) {
    return ({ production: "正式運用", lab: "実証・検証モード", development: "開発プレビュー" })[String(profile || "").toLowerCase()] || "開発プレビュー";
  }
  function renderOperationMode(health) {
    const profile = String(health.profile || "").toLowerCase();
    const label = profileLabel(profile);
    const isDev = profile !== "production";
    const anon = health.anonymousIngress ? "・anonymous ingress有効" : "";
    const detail = $("settingsProfile");
    if (detail) detail.textContent = isDev ? `${label}（正式運用には未対応${anon}）` : label;
    const chip = $("modeLabel");
    if (chip) { chip.textContent = isDev ? label : ""; chip.classList.toggle("dev", isDev); }
    renderTrainingBanner(health.trainingMode === true);
  }
  function renderTrainingBanner(active) {
    let banner = $("trainingBanner");
    if (!active) { if (banner) banner.remove(); return; }
    if (!banner) {
      banner = document.createElement("div");
      banner.id = "trainingBanner";
      banner.className = "training-banner";
      banner.setAttribute("role", "alert");
      document.body.prepend(banner);
    }
    banner.textContent = "訓練モード — この画面の情報はすべて訓練用です。実際の救助依頼・個人情報は含まれません。";
  }
  function conditionLabel(value) {
    return ({ LIFE_THREATENING: "命の危険", INJURED_OR_UNWELL: "けが・体調不良", MOBILITY_IMPAIRED: "自力移動困難", SUPPORT_NEEDED: "生活・医療支援" })[value] || value;
  }
  function needLabel(value) {
    return ({ WATER: "水", FOOD: "食料", MEDICINE: "薬・医療", RESCUE_TEAM: "救助隊", TRANSPORT: "移動支援" })[value] || value;
  }
  function isTerminal(request) { return ["COMPLETED", "UNABLE", "DUPLICATE"].includes(request.responseStatus); }
  function isImmediate(request) { return request.urgency === "IMMEDIATE" && request.action !== "CANCELLED"; }

  async function unlock(event) {
    event.preventDefault();
    const username = $("staffUsername").value.trim();
    const password = $("staffPassword").value;
    if (!username || !password) return;
    try {
      state.staff = await api("/api/auth/login", { method: "POST", headers: authHeaders(true), body: JSON.stringify({ username, password }) });
      $("setup").classList.add("hidden");
      $("staffNodeLabel").textContent = nodeId();
      $("settingsNode").textContent = nodeId();
      if ("Notification" in window && Notification.permission === "default") Notification.requestPermission();
      await refreshAll();
      armRefresh();
    } catch (error) {
      state.staff = null;
      $("staffPassword").setCustomValidity(error.status === 401 ? "利用者名またはパスワードが違います" : "接続できません");
      $("staffPassword").reportValidity();
      $("staffPassword").setCustomValidity("");
    }
  }

  async function lock() {
    try { await api("/api/auth/logout", { method: "POST", headers: authHeaders() }); } catch (_) { /* reset local UI either way */ }
    state.staff = null;
    stopAlarm();
    if (state.timer) clearInterval(state.timer);
    $("staffPassword").value = "";
    $("setup").classList.remove("hidden");
  }

  async function loadRequests() {
    const response = await api("/api/rescue/requests", { headers: authHeaders() });
    state.requests = response.items || [];
    $("requestCount").textContent = state.requests.filter((item) => !isTerminal(item)).length;
    if (!state.selectedId && state.requests.length) state.selectedId = state.requests[0].requestId;
    renderRequests();
    renderMaps();
    showCriticalIfNeeded();
    $("lastUpdated").textContent = `更新 ${fmtTime(response.generatedAtEpochMillis)}`;
  }

  function renderRequests() {
    const visible = state.requests.filter((request) => state.filter === "all" || !isTerminal(request));
    if (!visible.length) {
      $("requestList").innerHTML = '<p class="empty">該当する救助依頼はありません。</p>';
      renderDetail(null);
      return;
    }
    $("requestList").innerHTML = visible.map((request) => {
      const selected = request.requestId === state.selectedId;
      const critical = isImmediate(request) && request.responseStatus === "UNCONFIRMED";
      return `<button type="button" class="request-card ${selected ? "selected" : ""} ${critical ? "critical" : ""}" data-request="${escapeHtml(request.requestId)}">
        <span class="request-top"><span class="priority">${critical ? "命の危険・未確認" : statusLabel(request.responseStatus)}</span><time>${fmtTime(request.receivedAtEpochMillis)}</time></span>
        <strong>${request.personCount == null ? "人数不明" : `${request.personCount}人`} · ${request.conditions.map(conditionLabel).join(" / ") || "状態未記載"}</strong>
        <span>${escapeHtml(request.locationDescription || "GPS位置あり")}</span>
        <span class="fine">${request.assignedNodeId ? `担当: ${escapeHtml(request.assignedNodeId)}` : "担当未確定"}</span>
      </button>`;
    }).join("");
    $("requestList").querySelectorAll("[data-request]").forEach((button) => button.addEventListener("click", () => {
      state.selectedId = button.dataset.request;
      renderRequests(); renderMaps();
    }));
    renderDetail(state.requests.find((request) => request.requestId === state.selectedId));
  }

  function renderDetail(request) {
    if (!request) { $("selectedDetail").innerHTML = '<p class="empty">依頼を選択してください。</p>'; return; }
    const tags = [
      ...request.conditions.map(conditionLabel), ...request.supportNeeds.map(needLabel),
      request.elderlyPresent ? "高齢者" : null, request.childrenPresent ? "子ども" : null,
      request.pregnantPresent ? "妊娠中" : null, request.trapped ? "閉じ込め" : null,
      request.fireOrCollapseRisk ? "火災・倒壊危険" : null,
    ].filter(Boolean);
    const ownedElsewhere = request.assignedNodeId && request.assignedNodeId !== nodeId();
    const actions = nextActions(request).map(([status, label, danger]) =>
      `<button class="button ${danger ? "danger-outline" : "primary"}" data-status="${status}" ${ownedElsewhere ? "disabled" : ""}>${label}</button>`).join("");
    $("selectedDetail").innerHTML = `
      <div class="detail-head"><div><p class="eyebrow">${isImmediate(request) ? "IMMEDIATE" : "RESCUE REQUEST"}</p><h3>${request.personCount == null ? "人数不明" : `${request.personCount}人`} / ${statusLabel(request.responseStatus)}</h3></div><span class="status-chip">v${request.requestVersion}</span></div>
      ${ownedElsewhere ? `<p class="assignment-note">${escapeHtml(request.assignedNodeId)} が担当中です。</p>` : ""}
      <dl class="detail-grid"><dt>出所・信頼度</dt><dd>${escapeHtml(request.sourceChannel || "既存経路") } / ${escapeHtml(request.ingressAssurance || "暗号Envelopeの受信")}</dd><dt>GPS</dt><dd>${request.latitude?.toFixed(6) ?? "不明"}, ${request.longitude?.toFixed(6) ?? "不明"}</dd><dt>位置精度</dt><dd>${request.accuracyMeters == null ? "不明" : `約${Math.round(request.accuracyMeters)}m`}</dd><dt>位置取得</dt><dd>${fmtTime(request.locationCapturedAtEpochMillis)}</dd><dt>場所の補足</dt><dd>${escapeHtml(request.locationDescription || "なし")}</dd><dt>状態・タグ</dt><dd>${tags.map((tag) => `<span class="tag">${escapeHtml(tag)}</span>`).join(" ") || "なし"}</dd><dt>補足文</dt><dd class="free-text">${escapeHtml(request.freeText || "なし")}</dd><dt>中継端末</dt><dd>${request.uniqueCarrierCount}台</dd></dl>
      <div class="action-row">${actions}</div><section><h4>経路履歴</h4><div id="routeAttempts" class="fine">確認中</div></section>`;
    $("selectedDetail").querySelectorAll("[data-status]").forEach((button) => button.addEventListener("click", () => updateStatus(request.requestId, button.dataset.status)));
    loadRouteAttempts(request.requestId);
  }

  async function loadRouteAttempts(requestId) {
    const box = $("routeAttempts"); if (!box || !requestId) { if (box) box.textContent = "既存データの経路履歴はありません。"; return; }
    try { const attempts = await api(`/api/rescue/requests/${encodeURIComponent(requestId)}/routes`, { headers: authHeaders() }); box.textContent = attempts.length ? attempts.map((a) => `${a.routeType}: ${a.result}${a.result === "RECEIPT_CONFIRMED" ? "（署名Receipt確認済み）" : "（正式受領は未確認）"}`).join(" / ") : "経路履歴はありません。"; } catch (_) { box.textContent = "経路履歴を取得できません。"; }
  }

  function nextActions(request) {
    if (request.action === "CANCELLED" || isTerminal(request)) return [];
    const primary = ({ UNCONFIRMED: ["CONFIRMED", "確認して担当開始"], CONFIRMED: ["PREPARING", "対応準備を開始"], PREPARING: ["RESPONDING", "現地対応を開始"], RESCUE_REQUESTED: ["RESPONDING", "現地対応を開始"], RESPONDING: ["COMPLETED", "対応完了"] })[request.responseStatus];
    return [primary ? [...primary, false] : null, ["UNABLE", "対応不可", true]].filter(Boolean);
  }

  async function updateStatus(id, status) {
    try {
      const observed = state.requests.find((item) => item.requestId === id);
      await api(`/api/rescue/requests/${encodeURIComponent(id)}/status`, { method: "POST", headers: authHeaders(true), body: JSON.stringify({ status, expectedRequestVersion: observed?.requestVersion ?? null }) });
      await loadRequests();
    } catch (error) {
      alert(error.status === 409 ? "別の運用者が先に担当したか、状態の順序が正しくありません。更新してください。" : `状態更新に失敗しました: ${error.message}`);
    }
  }

  function showCriticalIfNeeded() {
    const urgent = state.requests.find((request) => isImmediate(request) && request.responseStatus === "UNCONFIRMED");
    if (!urgent) { $("criticalAlert").classList.add("hidden"); stopAlarm(); return; }
    state.selectedId = urgent.requestId;
    $("criticalSummary").textContent = `${urgent.personCount == null ? "人数不明" : `${urgent.personCount}人`} / ${urgent.locationDescription || "GPS位置を確認してください"}`;
    $("criticalAlert").classList.remove("hidden");
    $("ackCritical").onclick = () => updateStatus(urgent.requestId, "CONFIRMED");
    startAlarm();
  }

  function startAlarm() {
    if (state.soundTimer) return;
    const beep = () => {
      try {
        const context = new (window.AudioContext || window.webkitAudioContext)();
        const oscillator = context.createOscillator(); const gain = context.createGain();
        oscillator.frequency.value = 880; gain.gain.value = 0.08; oscillator.connect(gain); gain.connect(context.destination);
        oscillator.start(); oscillator.stop(context.currentTime + 0.22); oscillator.onended = () => context.close();
      } catch (_) { /* visual alert remains */ }
    };
    beep(); state.soundTimer = setInterval(beep, 1800);
  }
  function stopAlarm() { if (state.soundTimer) clearInterval(state.soundTimer); state.soundTimer = null; }

  function globalPixel(latitude, longitude, z) {
    const scale = 256 * 2 ** z; const sin = Math.sin(latitude * Math.PI / 180);
    return { x: (longitude + 180) / 360 * scale, y: (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * scale };
  }
  function geographicalPoint(x, y, z) {
    const scale = 256 * 2 ** z; const longitude = x / scale * 360 - 180;
    const latitude = Math.atan(Math.sinh(Math.PI - 2 * Math.PI * y / scale)) * 180 / Math.PI;
    return { latitude, longitude };
  }
  function clamp(value, minimum, maximum) { return Math.min(maximum, Math.max(minimum, value)); }
  function mapView(element) { return mapViews[element.id] || (mapViews[element.id] = { ...initialMapView }); }
  function requestsForMap(element) {
    return element.id === "rescueMap" ? state.requests.filter((item) => !isTerminal(item)) : state.requests;
  }
  function updateMapCenter(element, centerPixel) {
    const view = mapView(element); const point = geographicalPoint(centerPixel.x, centerPixel.y, view.zoom);
    view.latitude = clamp(point.latitude, mapBounds.south, mapBounds.north);
    view.longitude = clamp(point.longitude, mapBounds.west, mapBounds.east);
  }
  function panMap(element, deltaX, deltaY) {
    const view = mapView(element); const centerPixel = globalPixel(view.latitude, view.longitude, view.zoom);
    updateMapCenter(element, { x: centerPixel.x + deltaX, y: centerPixel.y + deltaY });
    renderTileMap(element, requestsForMap(element));
  }
  function zoomMap(element, requestedZoom, clientX, clientY) {
    const view = mapView(element); const nextZoom = clamp(requestedZoom, mapLimits.minZoom, mapLimits.maxZoom);
    if (Math.abs(nextZoom - view.zoom) < 0.001) return;
    const rectangle = element.getBoundingClientRect();
    const offsetX = (clientX ?? rectangle.left + rectangle.width / 2) - rectangle.left - rectangle.width / 2;
    const offsetY = (clientY ?? rectangle.top + rectangle.height / 2) - rectangle.top - rectangle.height / 2;
    const currentCenter = globalPixel(view.latitude, view.longitude, view.zoom);
    const focus = geographicalPoint(currentCenter.x + offsetX, currentCenter.y + offsetY, view.zoom);
    view.zoom = nextZoom;
    const nextFocus = globalPixel(focus.latitude, focus.longitude, nextZoom);
    updateMapCenter(element, { x: nextFocus.x - offsetX, y: nextFocus.y - offsetY });
    renderTileMap(element, requestsForMap(element));
  }
  function resetMap(element) {
    Object.assign(mapView(element), initialMapView, { zoom: clamp(initialMapView.zoom, mapLimits.minZoom, mapLimits.maxZoom) });
    renderTileMap(element, requestsForMap(element));
  }
  function bindMapInteractions(element) {
    if (element.dataset.mapInteractions === "true") return;
    element.dataset.mapInteractions = "true";
    element.tabIndex = 0;
    element.setAttribute("role", "application");
    element.setAttribute("aria-roledescription", "操作可能なオフライン地図");
    const interaction = { pointers: new Map(), pinchDistance: null };

    element.addEventListener("click", (event) => {
      const action = event.target.closest("[data-map-action]")?.dataset.mapAction;
      if (action === "zoom-in") zoomMap(element, Math.floor(mapView(element).zoom) + 1);
      if (action === "zoom-out") zoomMap(element, Math.ceil(mapView(element).zoom) - 1);
      if (action === "reset") resetMap(element);
    });
    element.addEventListener("wheel", (event) => {
      event.preventDefault();
      zoomMap(element, mapView(element).zoom + (event.deltaY < 0 ? 0.25 : -0.25), event.clientX, event.clientY);
    }, { passive: false });
    element.addEventListener("dblclick", (event) => {
      event.preventDefault();
      zoomMap(element, Math.floor(mapView(element).zoom) + 1, event.clientX, event.clientY);
    });
    element.addEventListener("keydown", (event) => {
      const key = event.key;
      if (["+", "=", "Add"].includes(key)) zoomMap(element, Math.floor(mapView(element).zoom) + 1);
      else if (["-", "_", "Subtract"].includes(key)) zoomMap(element, Math.ceil(mapView(element).zoom) - 1);
      else if (key === "ArrowLeft") panMap(element, -80, 0);
      else if (key === "ArrowRight") panMap(element, 80, 0);
      else if (key === "ArrowUp") panMap(element, 0, -80);
      else if (key === "ArrowDown") panMap(element, 0, 80);
      else if (key === "Home" || key === "0") resetMap(element);
      else return;
      event.preventDefault();
    });
    element.addEventListener("pointerdown", (event) => {
      if (event.target.closest("button")) return;
      interaction.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      element.setPointerCapture?.(event.pointerId);
      if (interaction.pointers.size === 2) {
        const [first, second] = [...interaction.pointers.values()];
        interaction.pinchDistance = Math.hypot(second.x - first.x, second.y - first.y);
      }
    });
    element.addEventListener("pointermove", (event) => {
      const previous = interaction.pointers.get(event.pointerId);
      if (!previous) return;
      interaction.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      if (interaction.pointers.size === 1) {
        panMap(element, previous.x - event.clientX, previous.y - event.clientY);
      } else if (interaction.pointers.size === 2) {
        const [first, second] = [...interaction.pointers.values()];
        const distance = Math.hypot(second.x - first.x, second.y - first.y);
        if (interaction.pinchDistance && distance > 0) {
          zoomMap(
            element,
            mapView(element).zoom + Math.log2(distance / interaction.pinchDistance),
            (first.x + second.x) / 2,
            (first.y + second.y) / 2,
          );
        }
        interaction.pinchDistance = distance;
      }
      event.preventDefault();
    });
    const finishPointer = (event) => {
      interaction.pointers.delete(event.pointerId);
      interaction.pinchDistance = null;
    };
    element.addEventListener("pointerup", finishPointer);
    element.addEventListener("pointercancel", finishPointer);
  }
  function renderTileMap(element, requests) {
    bindMapInteractions(element);
    if (!state.mapEnabled) {
      element.innerHTML = '<p class="empty map-unconfigured">地図は地域プロファイルで設定されていません。</p>';
      return;
    }
    const view = mapView(element);
    const viewportWidth = element.clientWidth || (element.classList.contains("large") ? 935 : 560);
    const viewportHeight = element.clientHeight || (element.classList.contains("large") ? 560 : 310);
    const tileZoom = clamp(Math.floor(view.zoom), mapLimits.minZoom, mapLimits.maxNativeZoom);
    const tileSize = 256 * 2 ** (view.zoom - tileZoom);
    const widthTiles = Math.ceil(viewportWidth / tileSize) + 2; const heightTiles = Math.ceil(viewportHeight / tileSize) + 2;
    const centerPixel = globalPixel(view.latitude, view.longitude, view.zoom);
    const viewportLeft = centerPixel.x - viewportWidth / 2; const viewportTop = centerPixel.y - viewportHeight / 2;
    const startX = Math.floor(viewportLeft / tileSize); const startY = Math.floor(viewportTop / tileSize);
    element.innerHTML = "";
    for (let row = 0; row < heightTiles; row += 1) for (let column = 0; column < widthTiles; column += 1) {
      const image = document.createElement("img"); image.className = "map-tile"; image.alt = "";
      image.draggable = false;
      image.src = `/api/map/tiles/${tileZoom}/${startX + column}/${startY + row}.png`;
      image.style.width = `${tileSize + 0.5}px`; image.style.height = `${tileSize + 0.5}px`;
      image.style.left = `${(startX + column) * tileSize - viewportLeft}px`;
      image.style.top = `${(startY + row) * tileSize - viewportTop}px`; element.appendChild(image);
    }
    requests.filter((request) => request.latitude != null && request.longitude != null).forEach((request) => {
      const point = globalPixel(request.latitude, request.longitude, view.zoom); const marker = document.createElement("button");
      marker.className = `map-marker ${isImmediate(request) ? "critical" : ""}`; marker.type = "button";
      marker.style.left = `${point.x - viewportLeft}px`; marker.style.top = `${point.y - viewportTop}px`;
      marker.title = `${request.personCount ?? "人数不明"} / ${statusLabel(request.responseStatus)}`;
      marker.addEventListener("click", () => { state.selectedId = request.requestId; activatePanel("rescue"); renderRequests(); renderMaps(); });
      element.appendChild(marker);
    });
    const controls = document.createElement("div"); controls.className = "map-controls";
    controls.innerHTML = `<button type="button" data-map-action="zoom-in" aria-label="地図を拡大" ${view.zoom >= mapLimits.maxZoom ? "disabled" : ""}>＋</button><span aria-live="polite">${view.zoom.toFixed(2).replace(/\\.00$/, "")}</span><button type="button" data-map-action="zoom-out" aria-label="地図を縮小" ${view.zoom <= mapLimits.minZoom ? "disabled" : ""}>−</button><button type="button" data-map-action="reset" aria-label="地図の表示位置と縮尺を戻す">戻す</button>`;
    element.appendChild(controls);
  }
  function renderMaps() { renderTileMap($("rescueMap"), state.requests.filter((item) => !isTerminal(item))); renderTileMap($("fullMap"), state.requests); }

  async function loadMapStatus() {
    const map = await api("/api/map/status", { headers: authHeaders() });
    state.mapEnabled = map.enabled === true;
    state.regionName = map.regionName || state.regionName;
    if (Number.isFinite(map.initialLatitude)) initialMapView.latitude = map.initialLatitude;
    if (Number.isFinite(map.initialLongitude)) initialMapView.longitude = map.initialLongitude;
    if (Number.isFinite(map.initialZoom)) initialMapView.zoom = map.initialZoom;
    if (Number.isFinite(map.south)) mapBounds.south = map.south;
    if (Number.isFinite(map.north)) mapBounds.north = map.north;
    if (Number.isFinite(map.west)) mapBounds.west = map.west;
    if (Number.isFinite(map.east)) mapBounds.east = map.east;
    mapLimits.minZoom = map.minZoom; mapLimits.maxNativeZoom = map.maxNativeZoom; mapLimits.maxZoom = map.maxZoom;
    if ($("regionName")) $("regionName").textContent = map.regionName || "設定地域";
    Object.values(mapViews).forEach((view) => { view.zoom = clamp(view.zoom, mapLimits.minZoom, mapLimits.maxZoom); });
    const ratio = map.expectedTiles ? map.cachedTiles / map.expectedTiles : 0;
    $("mapProgress").value = ratio; $("mapProgressLabel").textContent = `${map.cachedTiles} / ${map.expectedTiles} タイル保存済み（詳細 ${map.minZoom}〜${map.maxNativeZoom}、拡大 ${map.maxZoom} まで）${map.lastError ? ` / ${map.lastError}` : ""}`;
    $("mapStatus").textContent = map.state === "ready" ? "オフライン準備済み" : map.state === "preparing" ? "地図保存中" : "地図未完了";
    $("prepareMap").disabled = map.state === "preparing" || map.state === "ready";
    renderMaps();
  }
  async function prepareMap() { await api("/api/map/prepare", { method: "POST", headers: authHeaders() }); await loadMapStatus(); }

  function provenanceLabel(provenance) {
    if (!provenance) return "";
    const verification = { TRANSPORT_TLS_ONLY: "TLS接続のみ検証（内容署名なし）", CACHED_UNVERIFIED: "保存済み・未検証", UNVERIFIED: "未検証・取得不能" }[provenance.verification] || provenance.verification;
    const fetched = provenance.fetchedAtEpochMillis ? ` / 取得 ${fmtTime(provenance.fetchedAtEpochMillis)}` : "";
    return ` / 来歴: ${verification}${fetched}`;
  }
  async function loadReviewQueue() { const rows = await api("/api/review-queue", { headers: authHeaders() }); $("reviewQueue").innerHTML = rows.length ? rows.map((row) => `<article class="request-card"><strong>${escapeHtml(row.subjectToken)} / ${escapeHtml(row.state)}</strong><span>確認優先度: ${escapeHtml(row.priority)} · ${escapeHtml(row.rationale)}</span><span class="fine">${row.manualOverride ? "手動変更あり" : "ルール計算"}${row.profileExpired ? " · 支援情報は期限切れ" : ""}</span><button class="button quiet" data-review-subject="${escapeHtml(row.subjectToken)}" type="button">手動で確認状態を変更</button></article>`).join("") : '<p class="empty">要確認候補はありません。</p>'; $("reviewQueue").querySelectorAll("[data-review-subject]").forEach((button) => button.addEventListener("click", async () => { const stateValue=prompt("状態（確認済み / 再確認対象 / 本人申告あり / 第三者情報あり・要確認 / 端末観測のみ・本人未確認 / 未確認）", "再確認対象"); if(!stateValue)return; const priority=prompt("確認優先度（高 / 中 / 低 / 完了、空欄は自動）", ""); const note=prompt("安全なメモ（氏名・住所・詳細な医療情報は入力しない）", ""); try{await api(`/api/review-queue/${encodeURIComponent(button.dataset.reviewSubject)}`,{method:"POST",headers:authHeaders(true),body:JSON.stringify({subjectToken:button.dataset.reviewSubject,state:stateValue,priority:priority||null,note:note||null})});await loadReviewQueue();}catch(_){alert("手動変更を保存できませんでした。");} })); }
  async function submitObservation(event) { event.preventDefault(); try { await api("/api/observations", { method:"POST", headers:authHeaders(true), body:JSON.stringify({subjectToken:$("observationSubject").value.trim(),observationType:$("observationType").value,locationCell:$("observationCell").value.trim()||null}) }); event.target.reset(); await loadReviewQueue(); } catch (_) { alert("観測を登録できませんでした。"); } }
  async function saveProfile(event) { event.preventDefault(); const due = $("profileDue").value; if (!due) return; const supportFlags=[]; if($("profileMobility").checked)supportFlags.push("MOBILITY");if($("profilePower").checked)supportFlags.push("POWER");if($("profileChildren").checked)supportFlags.push("CHILDREN");try { await api("/api/support-profiles",{method:"POST",headers:authHeaders(true),body:JSON.stringify({subjectToken:$("profileSubject").value.trim(),supportFlags,reviewDueAtEpochMillis:new Date(`${due}T00:00:00Z`).getTime()})});event.target.reset();await loadReviewQueue();}catch(_){alert("支援プロファイルを保存できませんでした。");} }
  async function revokeProfile() { const subject=$("revokeSubject").value.trim(); if(!subject)return; try{await api(`/api/support-profiles/${encodeURIComponent(subject)}/revoke`,{method:"POST",headers:authHeaders()});$("revokeSubject").value="";await loadReviewQueue();}catch(_){alert("支援プロファイルを撤回できませんでした。");} }
  async function csvAction(importNow) { try { const body={kind:$("csvKind").value,csv:$("csvText").value,dryRun:!importNow}; const result=await api(importNow?"/api/import":"/api/import/preview",{method:"POST",headers:authHeaders(true),body:JSON.stringify(body)}); $("csvResult").textContent=`有効 ${result.validRows} / 重複 ${result.duplicateRows} / 拒否 ${result.rejectedRows}${result.errors?.length?`（${result.errors.join("; ")}）`:""}`; if(importNow&&result.rejectedRows===0) await loadReviewQueue(); } catch (_) { $("csvResult").textContent="CSVを処理できませんでした。"; } }

  async function loadOfficial() {
    const info = await api("/api/official-info", { headers: authHeaders() });
    $("officialAlert").textContent = info.urgent ? `公式情報: ${info.warningHeadline}` : `公式情報: ${info.warningHeadline}`;
    $("officialAlert").classList.toggle("urgent", info.urgent);
    $("warningDetail").innerHTML = `<h3>公式情報</h3><p>${escapeHtml(info.warningHeadline)}</p><ul>${info.warningStatuses.map((value) => `<li>${escapeHtml(value)}</li>`).join("") || "<li>発表状況なし</li>"}</ul><p class="fine">確認 ${fmtTime(info.checkedAtEpochMillis)}${info.usedCachedWarning ? "（保存済み情報）" : ""}${escapeHtml(provenanceLabel(info.provenance))}</p>`;
    $("officialSources").innerHTML = info.sources.map((source) => `<a class="source-card" href="${escapeHtml(source.url)}" target="_blank" rel="noopener"><strong>${escapeHtml(source.title)}</strong><span>${escapeHtml(source.organization)} 公式サイト</span></a>`).join("");
    if (info.urgent && !state.notifiedWarning && "Notification" in window && Notification.permission === "granted") {
      new Notification("Relay official alert", { body: info.warningHeadline }); state.notifiedWarning = true;
    }
  }

  async function refreshAll() {
    try {
      await Promise.all([loadRequests(), loadMapStatus(), loadOfficial(), loadReviewQueue(), api("/api/health").then((health) => { $("healthStatus").textContent = `Gateway ${health.status} / BLE ${health.bleBridgeStatus}`; renderOperationMode(health); })]);
      $("connectionDot").classList.add("online"); $("connectionLabel").textContent = "接続中";
    } catch (error) {
      if (error.status === 401) return lock();
      $("connectionDot").classList.remove("online"); $("connectionLabel").textContent = "再接続中";
    }
  }
  function armRefresh() { if (state.timer) clearInterval(state.timer); state.timer = setInterval(refreshAll, 5000); }
  function activatePanel(name) {
    document.querySelectorAll(".tab").forEach((tab) => tab.classList.toggle("active", tab.dataset.panel === name));
    document.querySelectorAll(".panel").forEach((panel) => panel.classList.toggle("active", panel.id === `panel-${name}`));
  }

  $("setupForm").addEventListener("submit", unlock); $("lockButton").addEventListener("click", lock);
  $("refreshButton").addEventListener("click", refreshAll); $("prepareMap").addEventListener("click", prepareMap);
  $("observationForm").addEventListener("submit", submitObservation); $("csvPreview").addEventListener("click", () => csvAction(false)); $("csvImport").addEventListener("click", () => csvAction(true));
  $("profileForm").addEventListener("submit", saveProfile); $("revokeProfile").addEventListener("click", revokeProfile);
  document.querySelectorAll(".tab").forEach((tab) => tab.addEventListener("click", () => activatePanel(tab.dataset.panel)));
  document.querySelectorAll(".filter").forEach((button) => button.addEventListener("click", () => { state.filter = button.dataset.filter; document.querySelectorAll(".filter").forEach((item) => item.classList.toggle("active", item === button)); renderRequests(); }));
  (async () => {
    try {
      state.staff = await api("/api/auth/session", { headers: authHeaders() });
      $("setup").classList.add("hidden");
      $("staffNodeLabel").textContent = nodeId();
      $("settingsNode").textContent = nodeId();
      await refreshAll(); armRefresh();
    } catch (_) { /* sign-in dialog remains visible */ }
  })();
})();
