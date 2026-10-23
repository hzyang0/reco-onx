from copy import deepcopy
from datetime import datetime, timezone
import unittest

from app.local_life import LocalConstraints, local_graph, merge_constraints, parse_constraints
from app.local_language import unresolved_requirements, withdraws_requirements, retain_requirements
from test_local_life import PRODUCT, FixtureTools


class NaturalLanguageRepairTests(unittest.TestCase):
    def test_spoken_money_is_an_exact_total_budget(self):
        for text, cents in [('只有十六块九',1690),('结账十八元五角以内',1850),('总账别过三十二元',3200),
                            ('预算二百元',20000),('合计最多一百二十元',12000),('总价上限改成十四元',1400),
                            ('预算20，不对，预算30',3000),('预算20改为30元',3000),
                            ('只有十六块九毛九分',1699),('最多十八元五分',1805)]:
            with self.subTest(text=text):
                patch, issues = parse_constraints(text)
                self.assertFalse(issues)
                self.assertEqual(patch['max_total_cents'], cents)

    def test_duration_and_clear_are_distinct_from_scheduled_delivery(self):
        for text, minutes in [('一刻钟送到',15),('一个钟头送达',60),('半小时内',30),('十八分钟以内',18),
                               ('不赶时间了，多久送都可以',None)]:
            with self.subTest(text=text):
                c, _, issues = merge_constraints(text, {'max_delivery_minutes':20}, {})
                self.assertFalse(issues)
                self.assertEqual(c['max_delivery_minutes'], minutes)
        for text in ['20分钟后送到','下午三点整送到','明天送到']:
            self.assertTrue(parse_constraints(text)[1])

    def test_full_names_and_single_composite_sku_are_preserved(self):
        for title in ['燕麦拿铁','牛奶面包组合','坚果零食组合','鸡肉米饭套餐','经典美式']:
            with self.subTest(title=title):
                patch, issues = parse_constraints('北区来两份'+title)
                self.assertFalse(issues)
                self.assertEqual(patch['product_query'],title)

    def test_repeating_category_does_not_clear_specific_product(self):
        old={'zone':'north','category':'coffee','product_query':'燕麦拿铁','quantity':1}
        c, _, issues = merge_constraints('咖啡改成两杯，其他照旧',old,{})
        self.assertFalse(issues)
        self.assertEqual((c['product_query'],c['quantity']),('燕麦拿铁',2))
        c, _, issues = merge_constraints('咖啡不要了，换成一份简餐',old,{})
        self.assertFalse(issues)
        self.assertEqual(c['category'],'meal')
        self.assertIsNone(c['product_query'])

    def test_explicit_correction_and_ambiguous_quantity(self):
        self.assertEqual(parse_constraints('两杯改成三杯')[0]['quantity'],3)
        self.assertEqual(parse_constraints('我不在北区了，改送南区')[0]['zone'],'south')
        self.assertTrue(parse_constraints('一杯或两杯都行')[1])
        self.assertTrue(parse_constraints('北区和东区之间')[1])

    def test_unverifiable_diet_requires_confirmation(self):
        for text in ['必须零乳糖','必须无麸质','一点辣都不能有','我过敏，不能有坚果']:
            self.assertTrue(unresolved_requirements(text))
            self.assertTrue(parse_constraints(text)[1])
        self.assertTrue(withdraws_requirements('刚才那条改口作废'))
        self.assertFalse(unresolved_requirements('取消零乳糖要求'))
        self.assertTrue(unresolved_requirements('取消零乳糖要求，但必须无糖'))

    def test_preferred_name_and_strict_name_have_distinct_modes(self):
        self.assertEqual(parse_constraints('优先燕麦拿铁，其他拿铁也行')[0]['product_query'],'燕麦拿铁')
        self.assertEqual(merge_constraints('想喝燕麦拿铁',{}, {})[0]['product_match_mode'],'prefer')
        self.assertEqual(parse_constraints('只要燕麦拿铁')[0]['product_match_mode'],'exact')
        c, _, _ = merge_constraints('燕麦拿铁改成两杯', {'product_query':'燕麦拿铁','product_match_mode':'exact'}, {})
        self.assertEqual(c['product_match_mode'],'exact')

    def test_exact_product_precedes_separate_confirmable_alternatives(self):
        class Tools(FixtureTools):
            def search_products(self,*args):
                return [deepcopy(PRODUCT)|{'title':'普通拿铁','product_id':1},
                        deepcopy(PRODUCT)|{'title':'燕麦拿铁','product_id':2,'unit_price_cents':1400}]
        def run(mode):
            return local_graph.invoke({'constraints':LocalConstraints(zone='north',category='coffee',product_query='燕麦拿铁',product_match_mode=mode,max_total_cents=3000).model_dump(),'tools':Tools(),'now':datetime.now(timezone.utc)})
        result=run('prefer')
        self.assertEqual([i['itemId'] for i in result['items']],[2])
        self.assertEqual([i['itemId'] for i in result['alternative_items']],[1])
        self.assertTrue(result['alternative_items'][0]['requiresConfirmation'])
        self.assertEqual(run('exact')['alternative_items'],[])

    def test_withdrawal_preserves_unrelated_and_negated_requirements(self):
        pending = ['必须零乳糖', '坚果过敏']
        self.assertEqual(retain_requirements(pending, '取消零乳糖要求'), ['坚果过敏'])
        self.assertEqual(retain_requirements(pending, '刚才的要求不要取消'), pending)
        self.assertEqual(retain_requirements(pending, '刚才那条要求作废'), pending)
        self.assertEqual(retain_requirements(pending, '取消所有饮食要求'), [])
        self.assertEqual(retain_requirements(['零乳糖且坚果过敏'], '取消零乳糖要求'), ['零乳糖且坚果过敏'])
        self.assertEqual(retain_requirements(pending, '取消零乳糖要求，但必须无糖'), ['坚果过敏'])

    def test_ambiguous_chinese_amounts_ask_instead_of_guessing(self):
        for message in ['预算一百二元', '预算十十元', '预算一二元']:
            self.assertTrue(parse_constraints(message)[1], message)
        self.assertEqual(parse_constraints('预算一百零二元')[0]['max_total_cents'], 10200)
        self.assertEqual(parse_constraints('预算一百二十元')[0]['max_total_cents'], 12000)

    def test_negated_clearing_never_relaxes_hard_constraints(self):
        previous = {'max_total_cents': 1690, 'max_delivery_minutes': 15, 'product_query': '燕麦拿铁'}
        for message in ['不要取消预算', '别取消送达限制', '不能取消商品限制']:
            c, _, issues = merge_constraints(message, previous, {})
            self.assertTrue(issues)
            for key, value in previous.items():
                self.assertEqual(c[key], value)
