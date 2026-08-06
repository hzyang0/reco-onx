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
    item_id BIGINT,
    category VARCHAR(32) NOT NULL,
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('exposure', 'view', 'click', 'cart', 'purchase')),
    event_time BIGINT NOT NULL,
    request_id VARCHAR(64),
    scene VARCHAR(32),
    CONSTRAINT fk_user_events_user
        FOREIGN KEY (user_id) REFERENCES user_profiles(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_user_events_user_time ON user_events(user_id, event_time DESC);
CREATE UNIQUE INDEX uk_user_event_request_item_type
    ON user_events(user_id, request_id, item_id, event_type);

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
    recall_reason VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_catalog_items_source_score ON catalog_items(source, base_score DESC);

CREATE TABLE goods_details (
    item_id BIGINT PRIMARY KEY,
    price INTEGER NOT NULL CHECK (price >= 0),
    stock INTEGER NOT NULL CHECK (stock >= 0),
    sale_status VARCHAR(16) NOT NULL CHECK (sale_status IN ('ONLINE', 'OFFLINE')),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_goods_item
        FOREIGN KEY (item_id) REFERENCES catalog_items(item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE live_details (
    item_id BIGINT PRIMARY KEY,
    room_id VARCHAR(32) NOT NULL UNIQUE,
    anchor_id VARCHAR(32) NOT NULL,
    heat INTEGER NOT NULL CHECK (heat >= 0),
    live_status VARCHAR(16) NOT NULL CHECK (live_status IN ('ONLINE', 'OFFLINE')),
    start_time TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_live_item
        FOREIGN KEY (item_id) REFERENCES catalog_items(item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ad_creatives (
    item_id BIGINT PRIMARY KEY,
    creative_id VARCHAR(32) NOT NULL UNIQUE,
    campaign_id VARCHAR(32) NOT NULL,
    promoted_item_id BIGINT,
    bid_cents INTEGER NOT NULL CHECK (bid_cents >= 0),
    remaining_budget_cents BIGINT NOT NULL CHECK (remaining_budget_cents >= 0),
    delivery_status VARCHAR(16) NOT NULL CHECK (delivery_status IN ('ONLINE', 'OFFLINE')),
    landing_url VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ad_item FOREIGN KEY (item_id) REFERENCES catalog_items(item_id),
    CONSTRAINT fk_ad_promoted_goods FOREIGN KEY (promoted_item_id) REFERENCES catalog_items(item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE user_events ADD CONSTRAINT fk_user_events_item
    FOREIGN KEY (item_id) REFERENCES catalog_items(item_id);

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
    (456, 'video_feed', 'B', 'DEFAULT'),
    (789, 'video_feed', 'A', 'DEFAULT'),
    (1000, 'mall', 'B', 'MALL_BOOST'),
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
    item_id, title, source, category, base_score, recall_reason
)
SELECT
    g.item_id,
    g.title,
    'goods',
    g.category,
    g.base_score,
    'preferred_category'
FROM seed_goods g;

CREATE TEMPORARY TABLE seed_live (
    item_id BIGINT PRIMARY KEY,
    title VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    base_score DOUBLE NOT NULL
);

INSERT INTO seed_live (item_id, title, category, base_score) VALUES
    (21001, '小户型收纳改造课', 'home', 0.99),
    (21002, '全屋灯光设计直播', 'home', 0.98),
    (21003, '睡眠空间诊断室', 'home', 0.97),
    (21004, '周末深度清洁挑战', 'home', 0.96),
    (21005, '智能家居联动实测', 'home', 0.95),
    (21006, '租房软装焕新指南', 'home', 0.94),
    (21007, '厨房动线改造课堂', 'home', 0.93),
    (21008, '阳台花园养成计划', 'home', 0.92),
    (21009, '亲子房布局公开课', 'home', 0.91),
    (21010, '旧家具翻新工坊', 'home', 0.90),
    (21011, '家庭空气质量测评', 'home', 0.89),
    (21012, '卫浴收纳现场改造', 'home', 0.88),
    (21013, '餐厅氛围布置课堂', 'home', 0.87),
    (21014, '宠物友好家居指南', 'home', 0.86),
    (21015, '无主灯方案在线答疑', 'home', 0.85),
    (21016, '衣柜空间规划直播', 'home', 0.84),
    (21017, '新房验收避坑课堂', 'home', 0.83),
    (21018, '居家香氛搭配课', 'home', 0.82),
    (21019, '家庭节能改造指南', 'home', 0.81),
    (21020, '整理师在线问诊', 'home', 0.80),
    (21101, '年度旗舰手机横评', 'digital', 0.99),
    (21102, '程序员桌搭改造间', 'digital', 0.98),
    (21103, '微单摄影入门课堂', 'digital', 0.97),
    (21104, '无线网络优化诊断', 'digital', 0.96),
    (21105, '机械键盘声音实验室', 'digital', 0.95),
    (21106, '降噪耳机通勤实测', 'digital', 0.94),
    (21107, '智能手表运动挑战', 'digital', 0.93),
    (21108, '家庭影音搭建指南', 'digital', 0.92),
    (21109, '移动办公装备评测', 'digital', 0.91),
    (21110, '电脑装机现场直播', 'digital', 0.90),
    (21111, '手机影像夜拍挑战', 'digital', 0.89),
    (21112, '数码配件避坑大会', 'digital', 0.88),
    (21113, '游戏外设延迟实测', 'digital', 0.87),
    (21114, '平板生产力公开课', 'digital', 0.86),
    (21115, '家庭存储搭建直播', 'digital', 0.85),
    (21116, '智能门锁安全测评', 'digital', 0.84),
    (21117, '无人机航拍入门课', 'digital', 0.83),
    (21118, '显示器色彩校准课', 'digital', 0.82),
    (21119, '旧电脑升级诊断', 'digital', 0.81),
    (21120, '智能硬件新品观察', 'digital', 0.80),
    (21201, '手冲咖啡风味课堂', 'food', 0.99),
    (21202, '家庭烘焙零失败教学', 'food', 0.98),
    (21203, '地方早餐探店直播', 'food', 0.97),
    (21204, '营养师一周食谱', 'food', 0.96),
    (21205, '火锅底料盲测现场', 'food', 0.95),
    (21206, '低糖甜品制作课', 'food', 0.94),
    (21207, '应季水果品鉴会', 'food', 0.93),
    (21208, '家常快手菜挑战', 'food', 0.92),
    (21209, '茶叶冲泡在线答疑', 'food', 0.91),
    (21210, '街头小吃寻味之旅', 'food', 0.90),
    (21211, '健康轻食搭配课', 'food', 0.89),
    (21212, '厨房新手刀工训练', 'food', 0.88),
    (21213, '进口零食开箱大会', 'food', 0.87),
    (21214, '家庭牛排烹饪实测', 'food', 0.86),
    (21215, '深夜食堂故事直播', 'food', 0.85),
    (21216, '儿童早餐创意课堂', 'food', 0.84),
    (21217, '地方名茶溯源直播', 'food', 0.83),
    (21218, '空气炸锅食谱实验', 'food', 0.82),
    (21219, '露营料理现场教学', 'food', 0.81),
    (21220, '节日家宴菜单设计', 'food', 0.80),
    (21301, '通勤穿搭一周示范', 'fashion', 0.99),
    (21302, '不同身型选衣课堂', 'fashion', 0.98),
    (21303, '衣橱断舍离改造', 'fashion', 0.97),
    (21304, '秋冬叠穿技巧直播', 'fashion', 0.96),
    (21305, '职场配色公开课', 'fashion', 0.95),
    (21306, '鞋包搭配在线诊断', 'fashion', 0.94),
    (21307, '国风服饰文化讲堂', 'fashion', 0.93),
    (21308, '基础款高级感挑战', 'fashion', 0.92),
    (21309, '男士理容入门直播', 'fashion', 0.91),
    (21310, '旅行胶囊衣橱指南', 'fashion', 0.90),
    (21311, '面料知识鉴别课堂', 'fashion', 0.89),
    (21312, '复古造型灵感分享', 'fashion', 0.88),
    (21313, '婚礼宾客穿搭指南', 'fashion', 0.87),
    (21314, '学生党平价搭配', 'fashion', 0.86),
    (21315, '发型与脸型匹配课', 'fashion', 0.85),
    (21316, '饰品叠戴技巧直播', 'fashion', 0.84),
    (21317, '换季衣物护理指南', 'fashion', 0.83),
    (21318, '时装周趋势解读', 'fashion', 0.82),
    (21319, '情侣出游造型设计', 'fashion', 0.81),
    (21320, '面试着装在线点评', 'fashion', 0.80),
    (21401, '零基础跑步训练营', 'sports', 0.99),
    (21402, '家庭力量训练直播', 'sports', 0.98),
    (21403, '马拉松备赛公开课', 'sports', 0.97),
    (21404, '瑜伽体态纠正课堂', 'sports', 0.96),
    (21405, '篮球基本功训练', 'sports', 0.95),
    (21406, '户外徒步路线分享', 'sports', 0.94),
    (21407, '游泳换气技巧教学', 'sports', 0.93),
    (21408, '骑行装备实战测评', 'sports', 0.92),
    (21409, '办公室拉伸跟练', 'sports', 0.91),
    (21410, '羽毛球步法训练', 'sports', 0.90),
    (21411, '露营营地搭建直播', 'sports', 0.89),
    (21412, '运动损伤预防讲座', 'sports', 0.88),
    (21413, '体脂管理答疑专场', 'sports', 0.87),
    (21414, '滑板入门动作教学', 'sports', 0.86),
    (21415, '青少年体能训练课', 'sports', 0.85),
    (21416, '攀岩安全基础课堂', 'sports', 0.84),
    (21417, '钓鱼新手技巧分享', 'sports', 0.83),
    (21418, '居家有氧燃脂跟练', 'sports', 0.82),
    (21419, '越野跑装备实测', 'sports', 0.81),
    (21420, '赛后恢复拉伸课堂', 'sports', 0.80);

INSERT INTO catalog_items (
    item_id, title, source, category, base_score, recall_reason
)
SELECT item_id, title, 'live', category, base_score, 'live_hot'
FROM seed_live;

CREATE TEMPORARY TABLE seed_ad (
    item_id BIGINT PRIMARY KEY,
    title VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    base_score DOUBLE NOT NULL
);

INSERT INTO seed_ad (item_id, title, category, base_score) VALUES
    (31001, '全屋焕新品牌日', 'home', 0.90),
    (31002, '收纳空间升级季', 'home', 0.89),
    (31003, '品质睡眠联合会场', 'home', 0.88),
    (31004, '智能家居体验周', 'home', 0.87),
    (31005, '清洁电器补贴专场', 'home', 0.86),
    (31006, '绿色家装公益计划', 'home', 0.85),
    (31007, '租房改造灵感季', 'home', 0.84),
    (31008, '厨房焕新狂欢节', 'home', 0.83),
    (31009, '舒适卫浴品牌周', 'home', 0.82),
    (31010, '夏日清凉家居节', 'home', 0.81),
    (31011, '亲子空间成长计划', 'home', 0.80),
    (31012, '环保家具认证推广', 'home', 0.79),
    (31013, '设计师家居联展', 'home', 0.78),
    (31014, '品质餐厨焕新会场', 'home', 0.77),
    (31015, '家庭安防守护行动', 'home', 0.76),
    (31016, '宠物友好生活节', 'home', 0.75),
    (31017, '冬季温暖居家季', 'home', 0.74),
    (31018, '新房入住福利周', 'home', 0.73),
    (31019, '家政服务体验计划', 'home', 0.72),
    (31020, '社区旧物焕新行动', 'home', 0.71),
    (31101, '智能设备新品季', 'digital', 0.90),
    (31102, '数码焕新补贴计划', 'digital', 0.89),
    (31103, '移动办公品牌周', 'digital', 0.88),
    (31104, '影音娱乐体验节', 'digital', 0.87),
    (31105, '开学装备采购季', 'digital', 0.86),
    (31106, '电竞生态合作会场', 'digital', 0.85),
    (31107, '影像创作者扶持计划', 'digital', 0.84),
    (31108, '智能穿戴健康行动', 'digital', 0.83),
    (31109, '家庭网络升级季', 'digital', 0.82),
    (31110, '正版软件优惠周', 'digital', 0.81),
    (31111, '绿色数码回收计划', 'digital', 0.80),
    (31112, '科技生活体验馆', 'digital', 0.79),
    (31113, '学生数码关爱计划', 'digital', 0.78),
    (31114, '摄影器材品牌联展', 'digital', 0.77),
    (31115, '家庭存储安全月', 'digital', 0.76),
    (31116, '智能出行装备周', 'digital', 0.75),
    (31117, '创客硬件扶持季', 'digital', 0.74),
    (31118, '企业采购专享会场', 'digital', 0.73),
    (31119, '数字生活会员日', 'digital', 0.72),
    (31120, '科技品牌周年庆', 'digital', 0.71),
    (31201, '城市美食探索季', 'food', 0.90),
    (31202, '精品咖啡品牌周', 'food', 0.89),
    (31203, '健康轻食推广计划', 'food', 0.88),
    (31204, '地方风味联合会场', 'food', 0.87),
    (31205, '家庭烘焙欢乐节', 'food', 0.86),
    (31206, '进口食品尝鲜季', 'food', 0.85),
    (31207, '早餐营养关爱行动', 'food', 0.84),
    (31208, '茶文化品牌联展', 'food', 0.83),
    (31209, '生鲜产地直达计划', 'food', 0.82),
    (31210, '零食会员狂欢日', 'food', 0.81),
    (31211, '传统糕点焕新季', 'food', 0.80),
    (31212, '有机食品认证推广', 'food', 0.79),
    (31213, '夏日饮品清凉节', 'food', 0.78),
    (31214, '家庭餐桌品质月', 'food', 0.77),
    (31215, '儿童营养成长计划', 'food', 0.76),
    (31216, '中秋团圆美食会场', 'food', 0.75),
    (31217, '年货风味采购节', 'food', 0.74),
    (31218, '餐饮品牌感恩周', 'food', 0.73),
    (31219, '乡村美食助农行动', 'food', 0.72),
    (31220, '厨房达人合作计划', 'food', 0.71),
    (31301, '都市通勤风尚季', 'fashion', 0.90),
    (31302, '设计师品牌联合展', 'fashion', 0.89),
    (31303, '青年潮流焕新计划', 'fashion', 0.88),
    (31304, '秋冬新品发布周', 'fashion', 0.87),
    (31305, '品质面料推广月', 'fashion', 0.86),
    (31306, '职场形象升级季', 'fashion', 0.85),
    (31307, '国风文化体验节', 'fashion', 0.84),
    (31308, '鞋履品牌周年庆', 'fashion', 0.83),
    (31309, '旅行穿搭灵感季', 'fashion', 0.82),
    (31310, '可持续时尚行动', 'fashion', 0.81),
    (31311, '春日轻装焕新会场', 'fashion', 0.80),
    (31312, '配饰潮流品牌周', 'fashion', 0.79),
    (31313, '校园穿搭关爱计划', 'fashion', 0.78),
    (31314, '婚礼造型服务季', 'fashion', 0.77),
    (31315, '男士风尚体验馆', 'fashion', 0.76),
    (31316, '亲子服饰欢乐节', 'fashion', 0.75),
    (31317, '冬季保暖联合会场', 'fashion', 0.74),
    (31318, '原创设计扶持计划', 'fashion', 0.73),
    (31319, '会员衣橱焕新日', 'fashion', 0.72),
    (31320, '城市街头文化周', 'fashion', 0.71),
    (31401, '全民健身品牌日', 'sports', 0.90),
    (31402, '城市跑步挑战赛', 'sports', 0.89),
    (31403, '户外探索装备季', 'sports', 0.88),
    (31404, '家庭运动推广计划', 'sports', 0.87),
    (31405, '青少年篮球公益营', 'sports', 0.86),
    (31406, '瑜伽生活方式周', 'sports', 0.85),
    (31407, '骑行城市合作计划', 'sports', 0.84),
    (31408, '游泳健身清凉季', 'sports', 0.83),
    (31409, '科学运动关爱行动', 'sports', 0.82),
    (31410, '露营生活体验节', 'sports', 0.81),
    (31411, '马拉松装备联合展', 'sports', 0.80),
    (31412, '办公室健康计划', 'sports', 0.79),
    (31413, '冬季滑雪品牌周', 'sports', 0.78),
    (31414, '全民球类欢乐季', 'sports', 0.77),
    (31415, '女性运动成长计划', 'sports', 0.76),
    (31416, '水上运动体验月', 'sports', 0.75),
    (31417, '专业训练会员日', 'sports', 0.74),
    (31418, '运动康复服务周', 'sports', 0.73),
    (31419, '校园体育助力行动', 'sports', 0.72),
    (31420, '年度户外品牌盛典', 'sports', 0.71);

INSERT INTO catalog_items (
    item_id, title, source, category, base_score, recall_reason
)
SELECT item_id, title, 'ad', category, base_score, 'commercial'
FROM seed_ad;

INSERT INTO goods_details (item_id, price, stock, sale_status)
SELECT
    g.item_id,
    g.price,
    CASE WHEN MOD(g.item_id, 17) = 0
         THEN 0 ELSE 8 + MOD(g.item_id, 90) END,
    CASE WHEN MOD(g.item_id, 29) = 0
         THEN 'OFFLINE' ELSE 'ONLINE' END
FROM seed_goods g;

INSERT INTO live_details (item_id, room_id, anchor_id, heat, live_status, start_time)
SELECT item_id,
       CONCAT('9', item_id),
       CONCAT('anchor-', MOD(item_id, 37) + 1),
       CASE WHEN MOD(item_id, 19) = 0 THEN 0 ELSE 3000 + MOD(item_id, 7000) END,
       CASE WHEN MOD(item_id, 31) = 0 THEN 'OFFLINE' ELSE 'ONLINE' END,
       TIMESTAMPADD(MINUTE, -MOD(item_id, 180), CURRENT_TIMESTAMP)
FROM seed_live;

INSERT INTO ad_creatives (
    item_id, creative_id, campaign_id, promoted_item_id, bid_cents,
    remaining_budget_cents, delivery_status, landing_url
)
SELECT item_id,
       CONCAT('8', item_id),
       CONCAT('campaign-', category),
       item_id - 20000,
       80 + MOD(item_id, 120),
       CASE WHEN MOD(item_id, 23) = 0 THEN 0 ELSE 100000 + MOD(item_id, 50) * 10000 END,
       CASE WHEN MOD(item_id, 31) = 0 THEN 'OFFLINE' ELSE 'ONLINE' END,
       CONCAT('https://example.invalid/campaign/', item_id)
FROM seed_ad;

DROP TEMPORARY TABLE seed_goods;
DROP TEMPORARY TABLE seed_live;
DROP TEMPORARY TABLE seed_ad;
