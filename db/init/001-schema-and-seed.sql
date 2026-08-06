SET NAMES utf8mb4;

CREATE TABLE user_profiles (
    user_id BIGINT PRIMARY KEY,
    age INTEGER NOT NULL CHECK (age > 0),
    new_user BOOLEAN NOT NULL,
    default_category VARCHAR(32) NOT NULL,
    province VARCHAR(32) NOT NULL,
    city VARCHAR(32) NOT NULL,
    persona_name VARCHAR(32) NOT NULL,
    persona_summary VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_events (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('view', 'click', 'cart', 'purchase')),
    event_time BIGINT NOT NULL,
    CONSTRAINT fk_user_events_user
        FOREIGN KEY (user_id) REFERENCES user_profiles(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_user_events_user_time ON user_events(user_id, event_time DESC);

CREATE TABLE experiment_assignments (
    user_id BIGINT NOT NULL,
    scene VARCHAR(32) NOT NULL,
    recall_exp VARCHAR(32) NOT NULL,
    rank_exp VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, scene),
    CONSTRAINT fk_experiment_user
        FOREIGN KEY (user_id) REFERENCES user_profiles(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE catalog_items (
    item_id BIGINT PRIMARY KEY,
    title VARCHAR(128) NOT NULL,
    source VARCHAR(16) NOT NULL CHECK (source IN ('goods', 'live', 'ad')),
    category VARCHAR(32) NOT NULL,
    base_score DOUBLE NOT NULL,
    recall_reason VARCHAR(64) NOT NULL,
    room_id VARCHAR(32),
    creative_id VARCHAR(32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_catalog_items_source_score ON catalog_items(source, base_score DESC);

CREATE TABLE inventory_snapshots (
    item_id BIGINT PRIMARY KEY,
    price INTEGER NOT NULL CHECK (price >= 0),
    stock INTEGER NOT NULL CHECK (stock >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ONLINE', 'OFFLINE')),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inventory_item
        FOREIGN KEY (item_id) REFERENCES catalog_items(item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO user_profiles (
    user_id, age, new_user, default_category, province, city, persona_name, persona_summary
) VALUES
    (123, 26, FALSE, 'home', '浙江', '杭州', '居家品质党', '偏爱收纳与家装，浏览、点击和加购意愿都较强'),
    (456, 31, FALSE, 'digital', '广东', '深圳', '数码发烧友', '关注高客单数码产品，购买行为明确且重视性能'),
    (789, 22, FALSE, 'food', '上海', '上海', '美食探索家', '热衷咖啡烘焙与特色零食，具有较高复购倾向'),
    (1000, 19, TRUE, 'fashion', '北京', '北京', '潮流新用户', '暂无历史行为，依靠默认画像完成冷启动推荐'),
    (2024, 35, FALSE, 'sports', '四川', '成都', '运动健康型', '长期关注跑步健身，偏好实用、耐用的运动装备');

INSERT INTO user_events (user_id, category, event_type, event_time) VALUES
    (123, 'home', 'view', 1721808000), (123, 'home', 'click', 1721894400),
    (123, 'home', 'cart', 1721980800), (123, 'digital', 'view', 1722067200),
    (456, 'digital', 'view', 1721808000), (456, 'digital', 'click', 1721894400),
    (456, 'digital', 'purchase', 1721980800), (456, 'home', 'view', 1722067200),
    (789, 'food', 'view', 1721808000), (789, 'food', 'click', 1721894400),
    (789, 'food', 'purchase', 1721980800), (789, 'fashion', 'view', 1722067200),
    (2024, 'sports', 'view', 1721808000), (2024, 'sports', 'click', 1721851200),
    (2024, 'sports', 'purchase', 1721894400), (2024, 'food', 'view', 1721980800);

INSERT INTO experiment_assignments (user_id, scene, recall_exp, rank_exp) VALUES
    (123, 'mall', 'A', 'MALL_BOOST'),
    (456, 'double_column', 'B', 'DEFAULT'),
    (789, 'single_column', 'A', 'DEFAULT'),
    (1000, 'new_user_card', 'B', 'MALL_BOOST'),
    (2024, 'buy_first', 'B', 'MALL_BOOST');

CREATE TEMPORARY TABLE seed_goods (
    base_id BIGINT NOT NULL,
    product_no INTEGER NOT NULL,
    title VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    base_score DOUBLE NOT NULL,
    base_price INTEGER NOT NULL
);

INSERT INTO seed_goods (base_id, product_no, title, category, base_score, base_price) VALUES
    (11000, 0, '北欧收纳箱', 'home', 0.98, 129),
    (11000, 1, '全光谱护眼台灯', 'home', 0.95, 259),
    (11000, 2, '天然乳胶枕', 'home', 0.92, 269),
    (11000, 3, '实木人体工学餐椅', 'home', 0.89, 699),
    (11100, 0, '无线降噪耳机', 'digital', 0.99, 799),
    (11100, 1, '客制化机械键盘', 'digital', 0.96, 429),
    (11100, 2, '氮化镓多口充电器', 'digital', 0.93, 149),
    (11100, 3, '4K 高刷显示器', 'digital', 0.90, 1999),
    (11200, 0, '精品手冲咖啡豆', 'food', 0.98, 98),
    (11200, 1, '每日坚果礼盒', 'food', 0.95, 69),
    (11200, 2, '低糖巴斯克蛋糕', 'food', 0.92, 128),
    (11200, 3, '地域风味牛肉干', 'food', 0.89, 89),
    (11300, 0, '通勤西装外套', 'fashion', 0.98, 459),
    (11300, 1, '轻量托特包', 'fashion', 0.95, 299),
    (11300, 2, '复古直筒牛仔裤', 'fashion', 0.92, 329),
    (11300, 3, '羊毛针织开衫', 'fashion', 0.89, 399),
    (11400, 0, '缓震竞速跑鞋', 'sports', 0.99, 599),
    (11400, 1, '智能运动手环', 'sports', 0.96, 269),
    (11400, 2, '可调节健身哑铃', 'sports', 0.93, 399),
    (11400, 3, '速干透气运动套装', 'sports', 0.90, 329);

CREATE TEMPORARY TABLE seed_variants (
    variant_no INTEGER PRIMARY KEY,
    suffix VARCHAR(16) NOT NULL,
    score_delta DOUBLE NOT NULL,
    price_percent INTEGER NOT NULL
);

INSERT INTO seed_variants (variant_no, suffix, score_delta, price_percent) VALUES
    (1, '', 0.000, 100),
    (2, ' · 轻享款', 0.012, 85),
    (3, ' · 进阶款', 0.024, 120),
    (4, ' · 旗舰款', 0.036, 165);

INSERT INTO catalog_items (
    item_id, title, source, category, base_score, recall_reason, room_id, creative_id
)
SELECT
    g.base_id + g.product_no * 4 + v.variant_no,
    CONCAT(g.title, v.suffix),
    'goods',
    g.category,
    ROUND(g.base_score - v.score_delta, 3),
    'preferred_category',
    NULL,
    NULL
FROM seed_goods g CROSS JOIN seed_variants v;

INSERT INTO catalog_items (item_id, title, source, category, base_score, recall_reason, room_id, creative_id) VALUES
    (21001, '收纳改造直播间', 'live', 'home', 0.82, 'live_hot', '900101', NULL),
    (21002, '居家好物秒杀', 'live', 'home', 0.76, 'live_hot', '900102', NULL),
    (21003, '数码新品首发', 'live', 'digital', 0.84, 'live_hot', '900103', NULL),
    (21004, '键鼠选购指南', 'live', 'digital', 0.77, 'live_hot', '900104', NULL),
    (21005, '咖啡风味实验室', 'live', 'food', 0.81, 'live_hot', '900105', NULL),
    (21006, '零食开箱直播', 'live', 'food', 0.75, 'live_hot', '900106', NULL),
    (21007, '秋季穿搭课堂', 'live', 'fashion', 0.83, 'live_hot', '900107', NULL),
    (21008, '包袋专场直播', 'live', 'fashion', 0.76, 'live_hot', '900108', NULL),
    (21009, '科学跑步训练营', 'live', 'sports', 0.85, 'live_hot', '900109', NULL),
    (21010, '家庭健身课堂', 'live', 'sports', 0.78, 'live_hot', '900110', NULL),
    (31001, '家居焕新满减专场', 'ad', 'home', 0.68, 'commercial', NULL, '800101'),
    (31002, '品质床品品牌会场', 'ad', 'home', 0.62, 'commercial', NULL, '800102'),
    (31003, '耳机新品推广', 'ad', 'digital', 0.70, 'commercial', NULL, '800103'),
    (31004, '智能设备以旧换新', 'ad', 'digital', 0.64, 'commercial', NULL, '800104'),
    (31005, '咖啡会员日活动', 'ad', 'food', 0.67, 'commercial', NULL, '800105'),
    (31006, '健康零食尝鲜会场', 'ad', 'food', 0.61, 'commercial', NULL, '800106'),
    (31007, '秋装限时活动', 'ad', 'fashion', 0.69, 'commercial', NULL, '800107'),
    (31008, '轻奢包袋品牌周', 'ad', 'fashion', 0.63, 'commercial', NULL, '800108'),
    (31009, '跑步装备品牌日', 'ad', 'sports', 0.71, 'commercial', NULL, '800109'),
    (31010, '全民健身补贴会场', 'ad', 'sports', 0.65, 'commercial', NULL, '800110');

INSERT INTO inventory_snapshots (item_id, price, stock, status)
SELECT
    g.base_id + g.product_no * 4 + v.variant_no,
    ROUND(g.base_price * v.price_percent / 100),
    CASE WHEN MOD(g.base_id + g.product_no * 4 + v.variant_no, 17) = 0
         THEN 0 ELSE 8 + MOD(g.base_id + g.product_no * 4 + v.variant_no, 90) END,
    CASE WHEN MOD(g.base_id + g.product_no * 4 + v.variant_no, 29) = 0
         THEN 'OFFLINE' ELSE 'ONLINE' END
FROM seed_goods g CROSS JOIN seed_variants v;

INSERT INTO inventory_snapshots (item_id, price, stock, status)
SELECT item_id,
       CASE source WHEN 'live' THEN 0 ELSE 79 + MOD(item_id, 12) * 50 END,
       CASE WHEN MOD(item_id, 19) = 0 THEN 0 ELSE 30 + MOD(item_id, 70) END,
       CASE WHEN MOD(item_id, 31) = 0 THEN 'OFFLINE' ELSE 'ONLINE' END
FROM catalog_items
WHERE source IN ('live', 'ad');

DROP TEMPORARY TABLE seed_variants;
DROP TEMPORARY TABLE seed_goods;
