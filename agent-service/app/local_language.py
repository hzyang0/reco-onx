"""Bounded Chinese requirement parsing. Unverifiable requirements fail closed."""
from decimal import Decimal
import re

ZONES = {"北区": "north", "南区": "south", "东区": "east"}
PRODUCTS = {
    "牛奶面包组合": ("grocery", "牛奶面包"), "坚果零食组合": ("grocery", "坚果"),
    "鸡肉米饭套餐": ("meal", "米饭"), "时蔬米饭套餐": ("meal", "米饭"),
    "燕麦拿铁": ("coffee", "拿铁"), "东区拿铁": ("coffee", "拿铁"),
    "经典美式": ("coffee", "美式"), "便利店美式": ("coffee", "美式"),
    "美式": ("coffee", "美式"), "拿铁": ("coffee", "拿铁"), "冷萃": ("coffee", "冷萃"),
    "牛奶": ("grocery", "牛奶"), "面包": ("grocery", "面包"), "坚果": ("grocery", "坚果"),
    "鸡肉": ("meal", "米饭"), "时蔬": ("meal", "米饭"),
}
CATEGORIES = {"咖啡": "coffee", "简餐": "meal", "米饭": "meal", "午餐": "meal", "晚餐": "meal",
              "零食": "grocery", "便利食品": "grocery"} | {k: v[0] for k, v in PRODUCTS.items()}
NUM = r"[负零〇一二两俩三四五六七八九十百千万点\d.]+"
FOOD = re.compile(r"乳糖|过敏|不能吃|忌口|无奶|不放|零咖啡因|低咖啡因|低卡|低热量|低钠|低盐|热的|冰的|少冰|多冰|无糖|低糖|零糖|少糖|脱脂|低脂|无麸质|素食|不含|去冰|不要辣|不能.*辣|辣.*不能|不加.*(?:奶|糖|辣)|必须.*(?:奶|糖)|只用.*奶")
FOOD_ISSUE = "缺少可验证的成分、过敏原或口味数据，不能确认这项饮食要求；请明确撤回该要求或结束本次查询"


def withdraws_requirements(message):
    # Negated withdrawal must never remove a safety-relevant condition.
    if re.search(r"(?:不要|别|不能|不想|不可|不许|无需).{0,4}(?:取消|撤回|撤销|作废)|(?:取消|撤回|撤销).{0,3}(?:不了|不行)", message):
        return False
    return bool(re.search(r"(?:刚才|上条|上一条|那条).*(?:作废|撤销|取消)|(?:取消|撤回|不再要求|不用).*(?:饮食|乳糖|过敏|无糖|低糖|配方|口味|成分|辣|要求)", message))


_REQUIREMENT_TOPICS = ("乳糖", "坚果", "花生", "过敏", "无糖", "低糖", "零糖", "少糖",
                       "麸质", "素食", "咖啡因", "奶", "辣", "冰", "热", "钠", "盐", "脂", "卡", "热量")


def retain_requirements(pending, message):
    """Remove only explicitly named requirements; ambiguous retractions fail closed.

    A compound stored clause is retained unless all its topics are retracted.
    This deliberately asks for a clearer withdrawal instead of losing an allergy.
    """
    remaining = list(pending)
    for clause in re.split(r"[，,。；;]|但是|但|不过", message):
        if not withdraws_requirements(clause):
            continue
        if re.search(r"(?:所有|全部).*(?:饮食|要求|条件)|(?:饮食|要求|条件).*(?:所有|全部)", clause):
            remaining = []
            continue
        named = {topic for topic in _REQUIREMENT_TOPICS if topic in clause}
        if named:
            remaining = [item for item in remaining
                         if not (topics := {t for t in _REQUIREMENT_TOPICS if t in item})
                         or not topics.issubset(named)]
        elif len(remaining) == 1 and re.search(r"刚才|上条|上一条|那条", clause):
            remaining = []
    return remaining


def unresolved_requirements(message):
    # Withdrawal applies only to the retracted clause, not a new requirement after it.
    requirements = []
    for clause in re.split(r"[，,。；;]|但是|但|不过", message):
        if FOOD.search(clause) and not withdraws_requirements(clause):
            requirements.append(clause.strip())
    return requirements


def number(text):
    text = text.replace("两", "二").replace("俩", "二").replace("〇", "零").replace("负", "-")
    try:
        return Decimal(text)
    except Exception:
        pass
    if text.startswith("-"):
        return -number(text[1:])
    if "点" in text:
        left, right = text.split("点", 1)
        digits = "零一二三四五六七八九"
        if not right or any(c not in digits for c in right):
            raise ValueError("invalid decimal")
        return number(left) + Decimal("0." + "".join(str(digits.index(c)) for c in right))
    sign = -1 if text.startswith("-") else 1
    text = text.lstrip("-")
    digits, units = "零一二三四五六七八九", {"十": 10, "百": 100, "千": 1000, "万": 10000}
    # Reject colloquial missing units (一百二), repeated units (十十), and
    # adjacent non-zero Chinese digits (一二). Guessing can change a hard budget.
    if re.search(r"[一二三四五六七八九]{2}|[百千万][一二三四五六七八九]$", text):
        raise ValueError("ambiguous Chinese number; use explicit units or digits")
    unit_values = [units[c] for c in text if c in units]
    if any(a <= b for a, b in zip(unit_values, unit_values[1:])):
        raise ValueError("invalid unit order")
    total = current = 0
    if not text:
        raise ValueError("empty number")
    for char in text:
        if char in digits:
            current = digits.index(char)
        elif char in units:
            total += (current or 1) * units[char]
            current = 0
        else:
            raise ValueError("invalid number")
    return Decimal(sign * (total + current))


def product_family(query):
    return PRODUCTS.get(query, (None, query))[1]


def _select(values, message, label, issues):
    distinct = list(dict.fromkeys(values))
    if len(distinct) > 1 and not re.search(r"不对|改成|改为|改送|换成|不是.+是", message):
        issues.append(f"{label}存在多个值，请明确选择一个")
        return None
    return values[-1] if values else None


def parse_request(message):
    patch, issues = {}, []
    if re.search(r"(?:不要|别|不能|不想|不可|不许).{0,4}(?:取消|清除|去掉).{0,6}(?:预算|时间|送达|商品|款式)", message):
        # A negated clearing command must never be interpreted as an actual
        # relaxation. Preserve the last valid snapshot and request clarification.
        return {}, ["检测到否定的条件撤销表达，已保留原条件；请明确要修改的预算、时限或商品"]
    # A full withdrawal of the previous turn is not a new product request.
    text = re.sub(r"(?:刚才|上条|上一条|那条)[^，,。；;]*(?:作废|撤销)", "", message)
    zone_hits = sorted((m.start(), value) for word, value in ZONES.items() for m in re.finditer(word, text))
    zone = _select([v for _, v in zone_hits], text, "配送区域", issues)
    if zone:
        patch['zone'] = zone
    occupied, products = set(), []
    for word in sorted(PRODUCTS, key=len, reverse=True):
        for match in re.finditer(word, text):
            span = set(range(match.start(), match.end()))
            if not span & occupied:
                products.append((match.start(), word))
                occupied |= span
    products.sort()
    if products and len({product_family(word) for _, word in products}) == 1 and re.search(r'优先|首选|其他.*也行', text):
        query = products[0][1]
    else:
        query = _select([word for _, word in products], text, "商品", issues)
    category_hits = sorted((m.start(), value) for word, value in CATEGORIES.items() for m in re.finditer(word, text))
    category = _select([v for _, v in category_hits], text, "品类", issues)
    if category:
        patch['category'] = category
    if query:
        patch['product_query'] = query
        if re.search(r"只要|只找|只喝|只买|只吃|仅限|必须|别给我别的|不能替换|不要其他", text):
            patch['product_match_mode'] = 'exact'
        elif re.search(r'优先|首选|其他.*也行|可以替换', text):
            patch['product_match_mode'] = 'prefer'
    if re.search(r"不限款式|取消商品限制|去掉款式|任何咖啡|什么咖啡都|咖啡都行", text):
        patch.update(product_query=None, product_match_mode='prefer')
    if any('不要' + word in text or '不喝' + word in text for word in PRODUCTS) and not re.search(r'换成|改成', text):
        issues.append('请直接说明想要的商品；暂不支持排除商品组合')
    if any('不要' + word in text for word in ['咖啡', '简餐', '零食']) and not re.search(r'换成|改成', text):
        issues.append('请明确改选一种品类')

    clear_budget = bool(re.search(r'不限预算|预算不限|取消预算|(?:价钱|价格|钱|预算).*(?:不设上限|无上限|不限制)', text))
    amounts = []
    # Capture complete monetary phrases, including 十六块九 / 十八元五角.
    money_pattern = rf'(?P<whole>-?{NUM})\s*(?:元|块)(?:(?P<fraction>[零一二三四五六七八九\d]+)\s*(?P<unit>角|毛|分)?(?:(?P<fen>[零一二三四五六七八九\d]+)分)?)?'
    for match in re.finditer(money_pattern, text):
        try:
            amount = number(match['whole']) * 100
            if match['whole'].startswith(('-', '负')):
                issues.append('预算不能为负数')
            if match['fraction']:
                fraction = number(match['fraction'])
                if not 0 <= fraction <= 9:
                    raise ValueError('fraction')
                amount += fraction * (1 if match['unit'] == '分' else 10)
            if match['fen']:
                fen = number(match['fen'])
                if match['unit'] not in ('角', '毛') or not 0 <= fen <= 9:
                    raise ValueError('fraction')
                amount += fen
            amounts.append(amount)
        except ValueError:
            issues.append('金额格式无法确认，请提供明确的到手总预算')
    if not amounts:
        for match in re.finditer(rf'(?:预算|到手价不超过|最多花|总价上限)\s*(?:改为|改成|提高到|降到|封顶)?\s*[¥￥]?\s*(-?{NUM})', text):
            try:
                amounts.append(number(match[1]) * 100)
            except ValueError:
                issues.append('金额格式无法确认')
    value = _select(amounts, text, '到手预算', issues)
    if value is not None:
        if value != value.to_integral_value():
            issues.append('预算最多支持两位小数')
        else:
            patch['max_total_cents'] = int(value)
    if clear_budget:
        patch['max_total_cents'] = None
    if re.search(r'预算|封顶|总价|最多花|只有.+(?:块|元)', text) and 'max_total_cents' not in patch:
        issues.append('请提供明确的最高到手预算，例如20元')
    if re.search(rf'{NUM}\s*(?:到|至|[-~～])\s*{NUM}\s*(?:元|块)', text):
        issues.append('价格区间暂不支持，请明确最高到手预算')
    if re.search(r'每杯|每份|单价', text) and amounts:
        issues.append('目前预算按订单到手总价计算，请明确总预算')
    if amounts and re.search(r'至少|不低于|起码|最低', text):
        issues.append('暂不支持最低消费要求，请明确最高到手预算')

    clear_time = bool(re.search(r'不限时间|时间不限|取消送达限制|不赶时间|多久送都可以|多久都行|多晚都行|不限制.*时间', text))
    times = []
    for match in re.finditer(rf'(-?{NUM}|半)\s*(?:个)?\s*(分钟|小时|钟头|刻钟)', text):
        try:
            val = Decimal('.5') if match[1] == '半' else number(match[1])
            times.append(val * {'分钟': 1, '小时': 60, '钟头': 60, '刻钟': 15}[match[2]])
        except ValueError:
            issues.append('送达时限无法确认')
    value = _select(times, text, '送达时限', issues)
    if value is not None:
        if value != value.to_integral_value():
            issues.append('送达时限须为整数分钟')
        else:
            patch['max_delivery_minutes'] = int(value)
    if clear_time:
        patch['max_delivery_minutes'] = None
    if times and re.search(r'至少|不早于|最早', text):
        issues.append('暂不支持最早送达限制，请明确最长等待时间')
    if re.search(r'分钟后|小时后|钟头后|(?:上午|下午|晚上|明天)|[一二三四五六七八九十\d]+点(?:整|钟)|[一二三四五六七八九十\d]+点.{0,4}(?:送到|送达)|别提前', text):
        issues.append('暂不支持预约指定时刻送达；请提供从现在起的最长等待分钟数')
    elif re.search(r'小时|分钟|刻钟|钟头', text) and 'max_delivery_minutes' not in patch:
        issues.append('请明确送达时限')

    quantities = []
    for match in re.finditer(rf'(-?{NUM})\s*(杯|份|件)', text):
        try:
            quantities.append(number(match[1]))
        except ValueError:
            issues.append('数量无法确认')
    value = _select(quantities, text, '单品数量', issues)
    if value is not None:
        if value != value.to_integral_value():
            issues.append('数量必须为整数')
        else:
            patch['quantity'] = int(value)
    if re.search(r'[几多]+\s*(杯|份|件)', text):
        issues.append('请明确单品数量，支持1～10件')
    if '西区' in text:
        issues.append('西区不在已建模的配送区域中')
    # “一刻钟送到” is a time phrase, not an unknown address.
    if re.search(r'我在|地址是|配送到|送到(?![，,。\s]*$)', text) and not zone and not times:
        issues.append('请明确配送区域：北区、南区或东区')
    if unresolved_requirements(message):
        issues.append(FOOD_ISSUE)
    if any(word in text for word in ['数码', '手机', '鲜花', '药品']):
        issues.append('本地生活演示只支持咖啡、简餐和便利食品')
    if '免配送费' in text:
        issues.append('暂不支持指定免运费要求，请用最高到手预算表达')
    return patch, list(dict.fromkeys(issues))
