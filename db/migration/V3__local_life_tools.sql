-- Entirely synthetic campus fixtures. Not real merchants, quotes or inventory.
CREATE TABLE local_merchants (
    merchant_id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    data_source VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE local_products (
    product_id BIGINT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    category VARCHAR(20) NOT NULL,
    unit_price_cents INT NOT NULL,
    packaging_cents INT NOT NULL,
    stock INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES local_merchants(merchant_id),
    CHECK (category IN ('coffee','meal','grocery')),
    CHECK (unit_price_cents >= 0 AND packaging_cents >= 0 AND stock >= 0),
    INDEX idx_local_product_category (category,enabled,merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE local_delivery_rules (
    merchant_id BIGINT NOT NULL,
    zone VARCHAR(16) NOT NULL,
    delivery_cents INT NOT NULL,
    eta_minutes INT NOT NULL,
    minimum_goods_cents INT NOT NULL,
    free_delivery_goods_cents INT NULL,
    coupon_threshold_cents INT NULL,
    coupon_discount_cents INT NOT NULL DEFAULT 0,
    valid_until TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (merchant_id,zone),
    FOREIGN KEY (merchant_id) REFERENCES local_merchants(merchant_id),
    CHECK (zone IN ('north','south','east')),
    CHECK (delivery_cents >= 0 AND eta_minutes > 0 AND minimum_goods_cents >= 0),
    CHECK (free_delivery_goods_cents IS NULL OR free_delivery_goods_cents >= 0),
    CHECK (coupon_threshold_cents IS NULL OR coupon_threshold_cents >= 0),
    CHECK (coupon_discount_cents >= 0),
    INDEX idx_local_delivery_zone (zone,merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE local_agent_sessions (
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    constraints_json JSON NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id,session_id),
    FOREIGN KEY (user_id) REFERENCES user_profiles(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE local_agent_tasks (
    task_id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    result_json JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_local_task_request (user_id,session_id,request_id),
    FOREIGN KEY (user_id,session_id) REFERENCES local_agent_sessions(user_id,session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO local_merchants (merchant_id,name,enabled,data_source) VALUES
(1,'北门咖啡实验店',TRUE,'synthetic-campus-v1'),
(2,'校园便利演示店',TRUE,'synthetic-campus-v1'),
(3,'食堂轻食演示店',TRUE,'synthetic-campus-v1'),
(4,'东区咖啡演示店',TRUE,'synthetic-campus-v1'),
(5,'暂停营业样例店',FALSE,'synthetic-campus-v1');

INSERT INTO local_products (product_id,merchant_id,title,category,unit_price_cents,packaging_cents,stock) VALUES
(101,1,'经典美式','coffee',800,100,40),
(102,1,'燕麦拿铁','coffee',1400,100,20),
(103,1,'售罄冷萃','coffee',700,100,0),
(201,2,'便利店美式','coffee',1200,0,30),
(202,2,'牛奶面包组合','grocery',900,50,25),
(203,2,'坚果零食组合','grocery',1600,50,15),
(301,3,'鸡肉米饭套餐','meal',1800,200,40),
(302,3,'时蔬米饭套餐','meal',2000,200,20),
(401,4,'东区拿铁','coffee',1100,100,30),
(501,5,'不可购买样例','coffee',100,0,100);

INSERT INTO local_delivery_rules
(merchant_id,zone,delivery_cents,eta_minutes,minimum_goods_cents,free_delivery_goods_cents,coupon_threshold_cents,coupon_discount_cents) VALUES
(1,'north',800,18,800,5000,2000,300),
(2,'north',200,25,1000,3000,2000,200),
(2,'south',400,40,1000,3000,2000,200),
(3,'north',300,30,1500,5000,3000,300),
(3,'south',400,35,1500,5000,3000,300),
(3,'east',600,60,1500,5000,3000,300),
(4,'east',500,15,1000,3000,NULL,0),
(5,'north',0,5,0,NULL,NULL,0);
