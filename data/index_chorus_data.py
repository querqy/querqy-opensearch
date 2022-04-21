# -*- coding: utf-8 -*-

from datetime import timezone
import json
import tqdm
from opensearchpy import OpenSearch
from opensearchpy.helpers import streaming_bulk

json_file_path = "./icecat-products-w_price-19k-20201127.json"
doc_attributes = ["id", "name", "title", "short_description", "img_500x500",
                  "date_released", "supplier", "price", "attr_t_product_colour"]
                  
host = 'localhost'
port = 9200
auth = ('admin', 'admin')  # For testing only. Don't store credentials in code.

client = OpenSearch(
    hosts=[{'host': host, 'port': port}],
    http_compress=True,  # enables gzip compression for request bodies
    http_auth=auth,
    use_ssl=True,
    verify_certs=False,
    ssl_assert_hostname=False,
    ssl_show_warn=False,
)

def fetch_data():
    with open(json_file_path, 'r') as j:
        contents = json.loads(j.read())
        for i, doc in enumerate(contents):
            filtered_doc = {key: doc[key] for key in doc_attributes if key in doc}
            yield filtered_doc

print("Indexing documents...")
progress = tqdm.tqdm(unit="docs", total=19406)
successes = 0
for ok, action in streaming_bulk(
    client=client, index="chorus-ecommerce-data", actions=fetch_data(),
):
    progress.update(1)
    successes += ok
print("Indexed %d/%d documents" % (successes, 19406))
