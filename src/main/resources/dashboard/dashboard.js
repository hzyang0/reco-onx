const elements = {
    form: document.querySelector("#recommendForm"),
    userId: document.querySelector("#userId"),
    catalogBadge: document.querySelector("#catalogBadge"),
    personaName: document.querySelector("#personaName"),
    personaSummary: document.querySelector("#personaSummary"),
    personaMeta: document.querySelector("#personaMeta"),
    openProfileCreator: document.querySelector("#openProfileCreator"),
    profileDialog: document.querySelector("#profileDialog"),
    profileForm: document.querySelector("#profileForm"),
    newUserId: document.querySelector("#newUserId"),
    profileFormError: document.querySelector("#profileFormError"),
    createProfileButton: document.querySelector("#createProfileButton"),
    closeProfileCreator: document.querySelector("#closeProfileCreator"),
    cancelProfileCreator: document.querySelector("#cancelProfileCreator"),
    scene: document.querySelector("#scene"),
    sceneHint: document.querySelector("#sceneHint"),
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
    feedbackStatus: document.querySelector("#feedbackStatus"),
    metricsBody: document.querySelector("#metricsBody"),
    refreshMetrics: document.querySelector("#refreshMetrics"),
    rawJson: document.querySelector("#rawJson")
};

const sourceOrder = ["goods", "live", "ad"];
const sceneHints = {
    mall: "商品为主，第 4、9 位穿插广告；直播候选只参与召回观测",
    video_feed: "直播/视频内容为主，第 4、9 位穿插广告；商品候选只参与召回观测",
    buy_first: "买家首页综合承接商品与直播，并在第 4、9 位穿插广告"
};
let consoleUsers = [];
let lastResponse = null;

async function requestJson(path, options = {}) {
    const response = await fetch(path, {
        ...options,
        headers: {"Accept": "application/json", ...(options.headers || {})},
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

async function loadConsoleData(selectedUserId = elements.userId.value) {
    const response = await requestJson("/api/console-data");
    consoleUsers = response.users || [];
    if (!consoleUsers.length) {
        throw new Error("MySQL 中没有可用的示例用户");
    }

    elements.userId.replaceChildren();
    consoleUsers.forEach((user) => {
        const option = document.createElement("option");
        option.value = String(user.userId);
        option.textContent = `${user.userId} · ${user.personaName}（${user.personaSummary}）`;
        elements.userId.append(option);
    });
    const selectedExists = consoleUsers.some((user) => String(user.userId) === String(selectedUserId));
    elements.userId.value = selectedExists ? String(selectedUserId) : String(consoleUsers[0].userId);
    elements.catalogBadge.textContent = `MySQL · ${response.userCount} 用户 · ${response.catalogCount} 候选`;
    renderSelectedUser(true);
}

function openProfileCreator() {
    const nextId = Math.max(...consoleUsers.map((user) => Number(user.userId)), 3000) + 1;
    elements.newUserId.value = String(nextId);
    elements.profileFormError.hidden = true;
    elements.profileFormError.textContent = "";
    elements.profileDialog.showModal();
}

function closeProfileCreator() {
    elements.profileDialog.close();
}

async function createProfile(event) {
    event.preventDefault();
    elements.createProfileButton.disabled = true;
    elements.createProfileButton.textContent = "正在写入 MySQL…";
    elements.profileFormError.hidden = true;
    try {
        const body = new URLSearchParams(new FormData(elements.profileForm));
        const created = await requestJson("/api/users", {
            method: "POST",
            headers: {"Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"},
            body
        });
        await loadConsoleData(created.userId);
        updateRequestPreview();
        closeProfileCreator();
        await runRecommendation();
    } catch (error) {
        elements.profileFormError.textContent = `创建失败：${error.message}`;
        elements.profileFormError.hidden = false;
    } finally {
        elements.createProfileButton.disabled = false;
        elements.createProfileButton.textContent = "保存画像并立即推荐";
    }
}

function renderSelectedUser(syncScene) {
    const user = consoleUsers.find((profile) => String(profile.userId) === elements.userId.value);
    if (!user) {
        return;
    }
    elements.personaName.textContent = `${user.personaName}（${user.personaSummary}）`;
    elements.personaSummary.textContent = `用户 ${user.userId} · ${user.location}`;
    elements.personaMeta.replaceChildren(
        profileTag(`${user.age} 岁`),
        profileTag(user.newUser ? "新用户 / 冷启动" : "有行为用户"),
        profileTag(`偏好 ${user.preferredCategory}`)
    );
    if (syncScene) {
        elements.scene.value = user.defaultScene;
    }
}

function profileTag(text) {
    const tag = document.createElement("span");
    tag.textContent = text;
    return tag;
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
    elements.sceneHint.textContent = sceneHints[elements.scene.value] || "按场景编排推荐内容";
}

async function runRecommendation() {
    setLoading(true);
    hideError();
    resetStageNodes();
    try {
        const response = await requestJson(currentPath());
        lastResponse = response;
        renderSummary(response);
        renderStages(response.debug?.stageCostMs || {});
        renderRecall(response.debug?.recallFanout || {});
        renderItems(response.items || []);
        elements.rawJson.textContent = JSON.stringify(response, null, 2);
        recordEvents("exposure", (response.items || []).map((item) => item.itemId), response)
            .catch(() => undefined);
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
    const recalledCount = Object.values(fanout.itemCountBySource || {})
        .reduce((total, count) => total + Number(count || 0), 0);
    elements.recallSummary.textContent = completed.length
        ? `${completed.length}/${sourceOrder.length} 来源完成 · ${recalledCount} 候选`
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
        const itemCount = fanout.itemCountBySource?.[source];
        chip.textContent = itemCount === undefined ? source : `${source} · ${itemCount}`;
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
        card.className = `item-card item-card-${item.source}`;

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
        attributes.append(...itemAttributeCells(item));

        const actions = feedbackActions(item);

        card.append(topLine, title, id, attributes, actions);
        elements.itemsGrid.append(card);
    });
}

function itemAttributeCells(item) {
    if (item.source === "live") {
        return [
            attributeCell("直播间", item.attrs?.room_id ?? "—"),
            attributeCell("热度", item.attrs?.heat ?? "—"),
            attributeCell("状态", item.attrs?.status ?? "—")
        ];
    }
    if (item.source === "ad") {
        return [
            attributeCell("创意 ID", item.attrs?.creative_id ?? "—"),
            attributeCell("广告计划", item.attrs?.campaign_id ?? "—"),
            attributeCell("状态", item.attrs?.status ?? "—")
        ];
    }
    return [
        attributeCell("价格", item.attrs?.price ? `¥${item.attrs.price}` : "—"),
        attributeCell("库存", item.attrs?.stock ?? "—"),
        attributeCell("状态", item.attrs?.status ?? "—")
    ];
}

function feedbackActions(item) {
    const actions = document.createElement("div");
    actions.className = "feedback-actions";
    const eventTypes = item.source === "goods"
        ? [["click", "点击"], ["cart", "加购"], ["purchase", "购买"]]
        : [["click", "感兴趣"]];
    eventTypes.forEach(([eventType, label]) => {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = label;
        button.addEventListener("click", () => submitFeedback(item, eventType, button));
        actions.append(button);
    });
    return actions;
}

async function submitFeedback(item, eventType, button) {
    if (!lastResponse) {
        return;
    }
    button.disabled = true;
    try {
        await recordEvents(eventType, [item.itemId], lastResponse);
        elements.feedbackStatus.textContent = `已记录 ${eventType}：${item.title}；画像已更新，正在重新推荐。`;
        await loadConsoleData(elements.userId.value);
        await runRecommendation();
    } catch (error) {
        showError(`行为上报失败：${error.message}`);
    } finally {
        button.disabled = false;
    }
}

async function recordEvents(eventType, itemIds, response) {
    if (!itemIds.length) {
        return null;
    }
    const body = new URLSearchParams({
        userId: String(response.userId),
        itemIds: itemIds.join(","),
        eventType,
        requestId: response.requestId,
        scene: response.scene
    });
    return requestJson("/api/events", {
        method: "POST",
        headers: {"Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"},
        body
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
elements.userId.addEventListener("change", () => {
    renderSelectedUser(true);
    updateRequestPreview();
});
elements.scene.addEventListener("change", updateRequestPreview);
elements.refreshMetrics.addEventListener("click", loadMetrics);
elements.openProfileCreator.addEventListener("click", openProfileCreator);
elements.closeProfileCreator.addEventListener("click", closeProfileCreator);
elements.cancelProfileCreator.addEventListener("click", closeProfileCreator);
elements.profileForm.addEventListener("submit", createProfile);

async function initializeConsole() {
    updateRequestPreview();
    loadHealth();
    try {
        await loadConsoleData();
        updateRequestPreview();
        await runRecommendation();
    } catch (error) {
        showError(`控制台初始化失败：${error.message}`);
        elements.catalogBadge.textContent = "MySQL · 数据不可用";
    }
}

initializeConsole();
