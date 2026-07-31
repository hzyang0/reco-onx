const elements = {
    form: document.querySelector("#recommendForm"),
    userId: document.querySelector("#userId"),
    scene: document.querySelector("#scene"),
    limit: document.querySelector("#limit"),
    limitOutput: document.querySelector("#limitOutput"),
    requestPath: document.querySelector("#requestPath"),
    submitButton: document.querySelector("#submitButton"),
    healthBadge: document.querySelector("#healthBadge"),
    errorBanner: document.querySelector("#errorBanner"),
    totalCost: document.querySelector("#totalCost"),
    returnedCount: document.querySelector("#returnedCount"),
    sceneSummary: document.querySelector("#sceneSummary"),
    recallStatus: document.querySelector("#recallStatus"),
    recallSummary: document.querySelector("#recallSummary"),
    requestId: document.querySelector("#requestId"),
    fanoutCost: document.querySelector("#fanoutCost"),
    recallSources: document.querySelector("#recallSources"),
    resultCaption: document.querySelector("#resultCaption"),
    itemsGrid: document.querySelector("#itemsGrid"),
    metricsBody: document.querySelector("#metricsBody"),
    refreshMetrics: document.querySelector("#refreshMetrics"),
    rawJson: document.querySelector("#rawJson")
};

const sourceOrder = ["goods", "live", "ad"];

async function requestJson(path) {
    const response = await fetch(path, {
        headers: {"Accept": "application/json"},
        cache: "no-store"
    });
    let payload;
    try {
        payload = await response.json();
    } catch {
        throw new Error(`接口返回了无法解析的内容（HTTP ${response.status}）`);
    }
    if (!response.ok) {
        throw new Error(payload.error || `请求失败（HTTP ${response.status}）`);
    }
    return payload;
}

async function loadHealth() {
    try {
        const health = await requestJson("/health");
        elements.healthBadge.className = "status-badge status-up";
        elements.healthBadge.innerHTML = '<span class="status-dot" aria-hidden="true"></span>服务正常';
        elements.healthBadge.title = `${health.service} · ${health.time}`;
    } catch (error) {
        elements.healthBadge.className = "status-badge status-down";
        elements.healthBadge.innerHTML = '<span class="status-dot" aria-hidden="true"></span>服务异常';
        elements.healthBadge.title = error.message;
    }
}

function currentPath() {
    const query = new URLSearchParams({
        userId: elements.userId.value,
        scene: elements.scene.value,
        limit: elements.limit.value
    });
    return `/recommend?${query.toString()}`;
}

function updateRequestPreview() {
    elements.limitOutput.value = elements.limit.value;
    elements.requestPath.textContent = currentPath();
}

async function runRecommendation() {
    setLoading(true);
    hideError();
    resetStageNodes();
    try {
        const response = await requestJson(currentPath());
        renderSummary(response);
        renderStages(response.debug?.stageCostMs || {});
        renderRecall(response.debug?.recallFanout || {});
        renderItems(response.items || []);
        elements.rawJson.textContent = JSON.stringify(response, null, 2);
        await loadMetrics();
    } catch (error) {
        showError(error.message);
    } finally {
        setLoading(false);
    }
}

function renderSummary(response) {
    elements.totalCost.textContent = formatMs(response.costMs);
    elements.returnedCount.textContent = String(response.items?.length || 0);
    elements.sceneSummary.textContent = `${response.scene} · user ${response.userId}`;
    const fanout = response.debug?.recallFanout || {};
    elements.recallStatus.textContent = fanout.status || "UNKNOWN";
    const completed = fanout.completedSources || [];
    elements.recallSummary.textContent = completed.length
        ? `${completed.length}/${sourceOrder.length} 来源完成`
        : "无召回来源";
    elements.requestId.textContent = response.requestId || "—";
    elements.requestId.title = response.requestId || "";
}

function resetStageNodes() {
    document.querySelectorAll("[data-stage]").forEach((node) => {
        node.classList.remove("stage-complete");
        node.querySelector(".node-cost").textContent = "运行中";
    });
}

function renderStages(stageCosts) {
    document.querySelectorAll("[data-stage]").forEach((node) => {
        const stage = node.dataset.stage;
        const value = stageCosts[stage];
        node.querySelector(".node-cost").textContent = value === undefined ? "未执行" : formatMs(value);
        node.classList.toggle("stage-complete", value !== undefined);
    });
}

function renderRecall(fanout) {
    const completed = new Set(fanout.completedSources || []);
    const timedOut = new Set(fanout.timedOutSources || []);
    const failed = new Set(Object.keys(fanout.failedSources || {}));
    elements.recallSources.replaceChildren();

    sourceOrder.forEach((source) => {
        const chip = document.createElement("span");
        chip.className = "source-chip";
        chip.textContent = source;
        if (completed.has(source)) {
            chip.classList.add("source-success");
            chip.title = `${source} 完成 · ${formatMs(fanout.sourceCostMs?.[source])}`;
        } else if (timedOut.has(source)) {
            chip.classList.add("source-timeout");
            chip.title = `${source} 超时`;
        } else if (failed.has(source)) {
            chip.classList.add("source-failed");
            chip.title = `${source} 失败 · ${fanout.failedSources[source]}`;
        } else {
            chip.classList.add("source-idle");
            chip.title = `${source} 未提交`;
        }
        elements.recallSources.append(chip);
    });
    elements.fanoutCost.textContent = formatMs(fanout.costMs);
}

function renderItems(items) {
    elements.itemsGrid.replaceChildren();
    elements.resultCaption.textContent = `共展示 ${items.length} 个 Item`;
    if (!items.length) {
        const empty = document.createElement("div");
        empty.className = "empty-state";
        empty.textContent = "当前请求没有返回 Item";
        elements.itemsGrid.append(empty);
        return;
    }

    items.forEach((item, index) => {
        const card = document.createElement("article");
        card.className = "item-card";

        const topLine = document.createElement("div");
        topLine.className = "item-topline";
        const source = document.createElement("span");
        source.className = `source-label source-${item.source}`;
        source.textContent = `${String(index + 1).padStart(2, "0")} · ${item.source}`;
        const score = document.createElement("span");
        score.className = "score";
        score.textContent = `score ${Number(item.score || 0).toFixed(3)}`;
        topLine.append(source, score);

        const title = document.createElement("h3");
        title.textContent = item.title;
        const id = document.createElement("div");
        id.className = "item-id";
        id.textContent = `ID ${item.itemId} · ${item.category}`;

        const attributes = document.createElement("div");
        attributes.className = "attribute-grid";
        attributes.append(
            attributeCell("价格", item.attrs?.price ? `¥${item.attrs.price}` : "—"),
            attributeCell("库存", item.attrs?.stock ?? "—"),
            attributeCell("状态", item.attrs?.status ?? "—")
        );

        card.append(topLine, title, id, attributes);
        elements.itemsGrid.append(card);
    });
}

function attributeCell(label, value) {
    const wrapper = document.createElement("span");
    wrapper.textContent = label;
    const strong = document.createElement("strong");
    strong.textContent = value;
    wrapper.append(strong);
    return wrapper;
}

async function loadMetrics() {
    try {
        const response = await requestJson("/metrics");
        renderMetrics(response.metrics || []);
    } catch (error) {
        elements.metricsBody.replaceChildren(tableMessage(`指标加载失败：${error.message}`));
    }
}

function renderMetrics(metrics) {
    elements.metricsBody.replaceChildren();
    if (!metrics.length) {
        elements.metricsBody.append(tableMessage("还没有指标数据"));
        return;
    }

    [...metrics]
        .sort((left, right) => left.name.localeCompare(right.name)
            || JSON.stringify(left.tags).localeCompare(JSON.stringify(right.tags)))
        .forEach((metric) => {
            const row = document.createElement("tr");
            row.append(
                cell(metric.name),
                tagsCell(metric.tags || {}),
                cell(metric.type),
                cell(metric.count),
                cell(metric.type === "timer" ? formatMs(metric.avg) : "—"),
                cell(metric.type === "timer" ? formatMs(metric.max) : "—")
            );
            elements.metricsBody.append(row);
        });
}

function tagsCell(tags) {
    const td = document.createElement("td");
    const wrapper = document.createElement("div");
    wrapper.className = "tag-list";
    const entries = Object.entries(tags);
    if (!entries.length) {
        wrapper.textContent = "—";
    } else {
        entries.forEach(([key, value]) => {
            const tag = document.createElement("span");
            tag.className = "metric-tag";
            tag.textContent = `${key}=${value}`;
            wrapper.append(tag);
        });
    }
    td.append(wrapper);
    return td;
}

function cell(value) {
    const td = document.createElement("td");
    td.textContent = String(value);
    return td;
}

function tableMessage(message) {
    const row = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 6;
    td.className = "table-empty";
    td.textContent = message;
    row.append(td);
    return row;
}

function formatMs(value) {
    if (value === undefined || value === null || Number.isNaN(Number(value))) {
        return "—";
    }
    return `${Number(value).toFixed(Number(value) % 1 === 0 ? 0 : 2)} ms`;
}

function setLoading(loading) {
    elements.submitButton.disabled = loading;
    elements.submitButton.firstElementChild.textContent = loading ? "链路执行中…" : "运行推荐链路";
}

function showError(message) {
    elements.errorBanner.textContent = message;
    elements.errorBanner.hidden = false;
}

function hideError() {
    elements.errorBanner.hidden = true;
    elements.errorBanner.textContent = "";
}

elements.form.addEventListener("submit", (event) => {
    event.preventDefault();
    runRecommendation();
});
elements.limit.addEventListener("input", updateRequestPreview);
elements.userId.addEventListener("input", updateRequestPreview);
elements.scene.addEventListener("change", updateRequestPreview);
elements.refreshMetrics.addEventListener("click", loadMetrics);

updateRequestPreview();
loadHealth();
runRecommendation();
