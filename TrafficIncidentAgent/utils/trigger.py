import requests

url = "http://localhost:1016/traffic-incident-agent/retrieve"

payload = ""
headers = {}

response = requests.request("POST", url, headers=headers, data=payload)

print(response.text)
