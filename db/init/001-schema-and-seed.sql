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
    item_id BIGINT PRIMARY KEY,
    title VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    base_score DOUBLE NOT NULL,
    price INTEGER NOT NULL
);

INSERT INTO seed_goods (item_id, title, category, base_score, price) VALUES
    (11001, '北欧收纳箱', 'home', 0.99, 129),
    (11002, '全光谱护眼台灯', 'home', 0.98, 259),
    (11003, '天然乳胶枕', 'home', 0.97, 269),
    (11004, '实木人体工学餐椅', 'home', 0.96, 699),
    (11005, '智能扫地机器人', 'home', 0.95, 1599),
    (11006, '静音空气净化器', 'home', 0.94, 1299),
    (11007, '恒温电热水壶', 'home', 0.93, 199),
    (11008, '抗菌床品四件套', 'home', 0.92, 499),
    (11009, '多功能料理锅', 'home', 0.91, 369),
    (11010, '免打孔浴室置物架', 'home', 0.90, 89),
    (11011, '云感记忆棉床垫', 'home', 0.89, 1899),
    (11012, '超声波加湿器', 'home', 0.88, 159),
    (11013, '可折叠晾衣架', 'home', 0.87, 239),
    (11014, '不粘炒锅套装', 'home', 0.86, 329),
    (11015, '香薰助眠灯', 'home', 0.85, 139),
    (11016, '模块化书架', 'home', 0.84, 599),
    (11017, '双层遮光窗帘', 'home', 0.83, 399),
    (11018, '感应式垃圾桶', 'home', 0.82, 179),
    (11019, '无线手持吸尘器', 'home', 0.81, 899),
    (11020, '恒温智能马桶盖', 'home', 0.80, 1199),
    (11101, '无线降噪耳机', 'digital', 0.99, 799),
    (11102, '客制化机械键盘', 'digital', 0.98, 429),
    (11103, '氮化镓多口充电器', 'digital', 0.97, 149),
    (11104, '4K 高刷显示器', 'digital', 0.96, 1999),
    (11105, '智能健康手表', 'digital', 0.95, 1299),
    (11106, '磁吸手机稳定器', 'digital', 0.94, 499),
    (11107, '便携固态硬盘', 'digital', 0.93, 699),
    (11108, '千兆无线路由器', 'digital', 0.92, 399),
    (11109, '轻薄办公平板', 'digital', 0.91, 2399),
    (11110, '电子墨水阅读器', 'digital', 0.90, 1099),
    (11111, '防抖运动相机', 'digital', 0.89, 1699),
    (11112, '桌面电容麦克风', 'digital', 0.88, 359),
    (11113, '高清便携投影仪', 'digital', 0.87, 1899),
    (11114, '低延迟触控笔', 'digital', 0.86, 299),
    (11115, '铝合金笔记本支架', 'digital', 0.85, 169),
    (11116, '防水蓝牙音箱', 'digital', 0.84, 329),
    (11117, '无线桌面充电站', 'digital', 0.83, 259),
    (11118, '迷你照片打印机', 'digital', 0.82, 599),
    (11119, '人体工学无线鼠标', 'digital', 0.81, 299),
    (11120, '智能家庭摄像头', 'digital', 0.80, 269),
    (11201, '精品手冲咖啡豆', 'food', 0.99, 98),
    (11202, '每日坚果礼盒', 'food', 0.98, 69),
    (11203, '低糖巴斯克蛋糕', 'food', 0.97, 128),
    (11204, '地域风味牛肉干', 'food', 0.96, 89),
    (11205, '高山原叶乌龙茶', 'food', 0.95, 119),
    (11206, '黑巧克力礼盒', 'food', 0.94, 79),
    (11207, '即食燕麦早餐杯', 'food', 0.93, 49),
    (11208, '川味牛油火锅底料', 'food', 0.92, 39),
    (11209, '番茄肉酱意面套装', 'food', 0.91, 59),
    (11210, '特级初榨橄榄油', 'food', 0.90, 139),
    (11211, '东北五常香米', 'food', 0.89, 99),
    (11212, '混合果蔬脆片', 'food', 0.88, 45),
    (11213, '常温希腊酸奶', 'food', 0.87, 72),
    (11214, '海盐苏打饼干', 'food', 0.86, 32),
    (11215, '天然成熟蜂蜜', 'food', 0.85, 88),
    (11216, '低糖蓝莓果酱', 'food', 0.84, 56),
    (11217, '云南鲜花饼礼盒', 'food', 0.83, 68),
    (11218, '日晒风味挂耳咖啡', 'food', 0.82, 75),
    (11219, '原切芝士片', 'food', 0.81, 48),
    (11220, '手工酸辣粉套装', 'food', 0.80, 42),
    (11301, '通勤西装外套', 'fashion', 0.99, 459),
    (11302, '轻量托特包', 'fashion', 0.98, 299),
    (11303, '复古直筒牛仔裤', 'fashion', 0.97, 329),
    (11304, '羊毛针织开衫', 'fashion', 0.96, 399),
    (11305, '防风长款风衣', 'fashion', 0.95, 599),
    (11306, '真丝方巾', 'fashion', 0.94, 169),
    (11307, '德训休闲鞋', 'fashion', 0.93, 389),
    (11308, '高腰百褶半身裙', 'fashion', 0.92, 269),
    (11309, '纯棉宽松衬衫', 'fashion', 0.91, 229),
    (11310, '复古飞行员夹克', 'fashion', 0.90, 499),
    (11311, '简约皮革腰带', 'fashion', 0.89, 139),
    (11312, '轻暖羽绒马甲', 'fashion', 0.88, 359),
    (11313, '精梳棉基础卫衣', 'fashion', 0.87, 199),
    (11314, '羊绒流苏围巾', 'fashion', 0.86, 299),
    (11315, '都市双肩背包', 'fashion', 0.85, 329),
    (11316, '复古金属腕表', 'fashion', 0.84, 459),
    (11317, '软底乐福鞋', 'fashion', 0.83, 359),
    (11318, '法式连衣裙', 'fashion', 0.82, 399),
    (11319, '廓形休闲西裤', 'fashion', 0.81, 299),
    (11320, '防晒轻薄外套', 'fashion', 0.80, 249),
    (11401, '缓震竞速跑鞋', 'sports', 0.99, 599),
    (11402, '智能运动手环', 'sports', 0.98, 269),
    (11403, '可调节健身哑铃', 'sports', 0.97, 399),
    (11404, '速干透气运动套装', 'sports', 0.96, 329),
    (11405, '专业瑜伽垫', 'sports', 0.95, 189),
    (11406, '碳纤维羽毛球拍', 'sports', 0.94, 459),
    (11407, '户外轻量登山杖', 'sports', 0.93, 239),
    (11408, '防水运动腰包', 'sports', 0.92, 129),
    (11409, '高弹护膝套装', 'sports', 0.91, 99),
    (11410, '可计数跳绳', 'sports', 0.90, 79),
    (11411, '家用折叠跑步机', 'sports', 0.89, 1899),
    (11412, '露营自动充气垫', 'sports', 0.88, 329),
    (11413, '双层运动水壶', 'sports', 0.87, 89),
    (11414, '儿童平衡滑板车', 'sports', 0.86, 499),
    (11415, '便携筋膜放松枪', 'sports', 0.85, 399),
    (11416, '室内悬挂训练带', 'sports', 0.84, 219),
    (11417, '专业篮球护腕', 'sports', 0.83, 69),
    (11418, '户外露营天幕', 'sports', 0.82, 599),
    (11419, '折叠便携钓鱼椅', 'sports', 0.81, 199),
    (11420, '防雾泳镜套装', 'sports', 0.80, 129);

INSERT INTO catalog_items (
    item_id, title, source, category, base_score, recall_reason, room_id, creative_id
)
SELECT
    g.item_id,
    g.title,
    'goods',
    g.category,
    g.base_score,
    'preferred_category',
    NULL,
    NULL
FROM seed_goods g;

INSERT INTO catalog_items (
    item_id, title, source, category, base_score, recall_reason, room_id, creative_id
)
SELECT
    g.item_id + 10000,
    CONCAT('直播精选｜', g.title),
    'live',
    g.category,
    ROUND(g.base_score - 0.12, 3),
    'live_hot',
    CONCAT('9', g.item_id),
    NULL
FROM seed_goods g;

INSERT INTO catalog_items (
    item_id, title, source, category, base_score, recall_reason, room_id, creative_id
)
SELECT
    g.item_id + 20000,
    CONCAT('品牌活动｜', g.title),
    'ad',
    g.category,
    ROUND(g.base_score - 0.25, 3),
    'commercial',
    NULL,
    CONCAT('8', g.item_id)
FROM seed_goods g;

INSERT INTO inventory_snapshots (item_id, price, stock, status)
SELECT
    g.item_id,
    g.price,
    CASE WHEN MOD(g.item_id, 17) = 0
         THEN 0 ELSE 8 + MOD(g.item_id, 90) END,
    CASE WHEN MOD(g.item_id, 29) = 0
         THEN 'OFFLINE' ELSE 'ONLINE' END
FROM seed_goods g;

INSERT INTO inventory_snapshots (item_id, price, stock, status)
SELECT item_id,
       CASE source WHEN 'live' THEN 0 ELSE 79 + MOD(item_id, 12) * 50 END,
       CASE WHEN MOD(item_id, 19) = 0 THEN 0 ELSE 30 + MOD(item_id, 70) END,
       CASE WHEN MOD(item_id, 31) = 0 THEN 'OFFLINE' ELSE 'ONLINE' END
FROM catalog_items
WHERE source IN ('live', 'ad');

DROP TEMPORARY TABLE seed_goods;
