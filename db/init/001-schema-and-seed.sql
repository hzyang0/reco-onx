CREATE TABLE user_profiles (
    user_id BIGINT PRIMARY KEY,
    age INTEGER NOT NULL CHECK (age > 0),
    new_user BOOLEAN NOT NULL,
    default_category VARCHAR(32) NOT NULL,
    province VARCHAR(32) NOT NULL,
    city VARCHAR(32) NOT NULL
);

CREATE TABLE user_events (
    event_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES user_profiles(user_id),
    category VARCHAR(32) NOT NULL,
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('view', 'click', 'cart', 'purchase')),
    event_time BIGINT NOT NULL
);
CREATE INDEX idx_user_events_user_time ON user_events(user_id, event_time DESC);

CREATE TABLE experiment_assignments (
    user_id BIGINT NOT NULL REFERENCES user_profiles(user_id),
    scene VARCHAR(32) NOT NULL,
    recall_exp VARCHAR(32) NOT NULL,
    rank_exp VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, scene)
);

CREATE TABLE catalog_items (
    item_id BIGINT PRIMARY KEY,
    title VARCHAR(128) NOT NULL,
    source VARCHAR(16) NOT NULL CHECK (source IN ('goods', 'live', 'ad')),
    category VARCHAR(32) NOT NULL,
    base_score DOUBLE PRECISION NOT NULL,
    recall_reason VARCHAR(64) NOT NULL,
    room_id VARCHAR(32),
    creative_id VARCHAR(32)
);
CREATE INDEX idx_catalog_items_source_score ON catalog_items(source, base_score DESC);

CREATE TABLE inventory_snapshots (
    item_id BIGINT PRIMARY KEY REFERENCES catalog_items(item_id),
    price INTEGER NOT NULL CHECK (price >= 0),
    stock INTEGER NOT NULL CHECK (stock >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ONLINE', 'OFFLINE')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO user_profiles (user_id, age, new_user, default_category, province, city) VALUES
    (123, 26, FALSE, 'home', '浙江', '杭州'),
    (456, 31, FALSE, 'digital', '广东', '深圳'),
    (789, 22, FALSE, 'food', '上海', '上海'),
    (1000, 19, TRUE, 'fashion', '北京', '北京'),
    (2024, 35, FALSE, 'home', '四川', '成都');

INSERT INTO user_events (user_id, category, event_type, event_time) VALUES
    (123, 'home', 'view', 1721808000), (123, 'home', 'click', 1721894400),
    (123, 'home', 'cart', 1721980800), (123, 'digital', 'view', 1722067200),
    (456, 'digital', 'view', 1721808000), (456, 'digital', 'click', 1721894400),
    (456, 'digital', 'purchase', 1721980800), (456, 'home', 'view', 1722067200),
    (789, 'food', 'view', 1721808000), (789, 'food', 'click', 1721894400),
    (789, 'food', 'purchase', 1721980800), (789, 'fashion', 'view', 1722067200),
    (2024, 'home', 'view', 1721808000), (2024, 'home', 'purchase', 1721894400);

INSERT INTO experiment_assignments (user_id, scene, recall_exp, rank_exp) VALUES
    (123, 'mall', 'A', 'MALL_BOOST'),
    (456, 'double_column', 'B', 'DEFAULT'),
    (789, 'new_user_card', 'A', 'DEFAULT'),
    (1000, 'new_user_card', 'B', 'MALL_BOOST'),
    (2024, 'mall', 'B', 'MALL_BOOST');

INSERT INTO catalog_items (item_id, title, source, category, base_score, recall_reason, room_id, creative_id) VALUES
    (11001, '北欧收纳箱', 'goods', 'home', 0.91, 'preferred_category', NULL, NULL),
    (11002, '护眼台灯', 'goods', 'home', 0.89, 'preferred_category', NULL, NULL),
    (11003, '天然乳胶枕', 'goods', 'home', 0.86, 'preferred_category', NULL, NULL),
    (11004, '实木餐椅', 'goods', 'home', 0.84, 'preferred_category', NULL, NULL),
    (11005, '无线降噪耳机', 'goods', 'digital', 0.92, 'preferred_category', NULL, NULL),
    (11006, '机械键盘', 'goods', 'digital', 0.88, 'preferred_category', NULL, NULL),
    (11007, '氮化镓充电器', 'goods', 'digital', 0.83, 'preferred_category', NULL, NULL),
    (11008, '4K 显示器', 'goods', 'digital', 0.87, 'preferred_category', NULL, NULL),
    (11009, '通勤西装外套', 'goods', 'fashion', 0.90, 'preferred_category', NULL, NULL),
    (11010, '轻量托特包', 'goods', 'fashion', 0.86, 'preferred_category', NULL, NULL),
    (11011, '运动跑鞋', 'goods', 'fashion', 0.82, 'preferred_category', NULL, NULL),
    (11012, '精品咖啡豆', 'goods', 'food', 0.91, 'preferred_category', NULL, NULL),
    (11013, '坚果零食礼盒', 'goods', 'food', 0.85, 'preferred_category', NULL, NULL),
    (11014, '巴斯克蛋糕', 'goods', 'food', 0.81, 'preferred_category', NULL, NULL),
    (21001, '收纳改造直播间', 'live', 'home', 0.77, 'live_hot', '900101', NULL),
    (21002, '居家好物秒杀', 'live', 'home', 0.73, 'live_hot', '900102', NULL),
    (21003, '数码新品首发', 'live', 'digital', 0.79, 'live_hot', '900103', NULL),
    (21004, '键鼠选购指南', 'live', 'digital', 0.74, 'live_hot', '900104', NULL),
    (21005, '秋季穿搭课堂', 'live', 'fashion', 0.78, 'live_hot', '900105', NULL),
    (21006, '包袋专场直播', 'live', 'fashion', 0.72, 'live_hot', '900106', NULL),
    (21007, '咖啡风味实验室', 'live', 'food', 0.76, 'live_hot', '900107', NULL),
    (21008, '零食开箱直播', 'live', 'food', 0.71, 'live_hot', '900108', NULL),
    (31001, '耳机新品推广', 'ad', 'digital', 0.66, 'commercial', NULL, '800101'),
    (31002, '家居满减专场', 'ad', 'home', 0.62, 'commercial', NULL, '800102'),
    (31003, '秋装限时活动', 'ad', 'fashion', 0.64, 'commercial', NULL, '800103'),
    (31004, '咖啡会员活动', 'ad', 'food', 0.63, 'commercial', NULL, '800104'),
    (31005, '品牌大促会场', 'ad', 'home', 0.60, 'commercial', NULL, '800105');

INSERT INTO inventory_snapshots (item_id, price, stock, status) VALUES
    (11001, 129, 48, 'ONLINE'), (11002, 159, 35, 'ONLINE'),
    (11003, 269, 22, 'ONLINE'), (11004, 699, 0, 'ONLINE'),
    (11005, 799, 43, 'ONLINE'), (11006, 429, 18, 'ONLINE'),
    (11007, 149, 0, 'ONLINE'), (11008, 1999, 11, 'ONLINE'),
    (11009, 459, 20, 'ONLINE'), (11010, 299, 27, 'ONLINE'),
    (11011, 389, 16, 'ONLINE'), (11012, 98, 60, 'ONLINE'),
    (11013, 69, 0, 'ONLINE'), (11014, 128, 9, 'OFFLINE'),
    (21001, 0, 999, 'ONLINE'), (21002, 0, 999, 'ONLINE'),
    (21003, 0, 999, 'ONLINE'), (21004, 0, 999, 'ONLINE'),
    (21005, 0, 999, 'ONLINE'), (21006, 0, 999, 'ONLINE'),
    (21007, 0, 999, 'ONLINE'), (21008, 0, 999, 'ONLINE'),
    (31001, 699, 30, 'ONLINE'), (31002, 99, 40, 'ONLINE'),
    (31003, 399, 26, 'ONLINE'), (31004, 79, 55, 'ONLINE'),
    (31005, 159, 44, 'ONLINE');
