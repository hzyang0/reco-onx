import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from fastapi import HTTPException
from app.main import app

class WebSocketProtocolTests(unittest.TestCase):
    def test_bad_frames_do_not_break_following_messages(self):
        with patch('app.main.resolve_demo_session', side_effect=HTTPException(401, 'invalid token')), TestClient(app).websocket_connect('/ws/chat') as ws:
            for frame in ['{broken', '[]', 'null', '42']:
                ws.send_text(frame)
                self.assertEqual(ws.receive_json()['event'], 'agent_error')
            ws.send_json({'token': 'invalid'})
            self.assertEqual(ws.receive_json()['event'], 'agent_error')

    def test_structured_constraints_reach_executor(self):
        async def execute(request):
            self.assertEqual(request.constraints.product_query, '未建模拿铁')
            self.assertIsNone(request.constraints.max_delivery_minutes)
            return {'toolTrace': [], 'answer': 'checked'}
        with patch('app.main.resolve_demo_session', return_value={'user_id':789,'session_id':'ws-test'}), patch('app.main.execute', side_effect=execute):
            with TestClient(app).websocket_connect('/ws/chat') as ws:
                ws.send_json({'token':'demo','message':'按已确认的条件重新查询','workflow':'local_life','constraints':{'product_query':'未建模拿铁','max_delivery_minutes':None}})
                self.assertEqual(ws.receive_json()['event'], 'agent_started')
                self.assertEqual(ws.receive_json()['event'], 'agent_completed')
