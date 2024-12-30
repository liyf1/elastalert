# -*- coding: utf-8 -*-
import json
import requests
import uuid

def get_tokens_from_aibase():
    open_url = "https://maas-gz-api.ai-yuanjing.com/openapi/service/v1/oauth/2a9ed817a2a545c7986d6816caf2bbc1/token"
    tmp_headers = {
        "grant_type": "client_credentials",
        "client_id": "a351558f1762452289f3dd57d1b4aa35",
        "client_secret": "6dbe4c33b515418694e4aec404d72049"
    }
    ret_json = requests.post(open_url, data=tmp_headers, verify=False)
    ret_json = json.loads(ret_json.text)
    return f'Bearer {ret_json["data"]["access_token"]}'

def generate_msg_id():
    return f'monitor_{str(uuid.uuid4())}'