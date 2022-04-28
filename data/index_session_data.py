# -*- coding: utf-8 -*-

import datetime
import json
import tqdm
from opensearchpy import OpenSearch
from opensearchpy.helpers import streaming_bulk
import uuid
import random
import time
    
def str_time_prop(start, end, time_format, prop):
    """Get a time at a proportion of a range of two formatted times.

    start and end should be strings specifying times formatted in the
    given format (strftime-style), giving an interval [start, end].
    prop specifies how a proportion of the interval to be taken after
    start.  The returned time will be in the specified format.
    """

    stime = time.mktime(time.strptime(start, time_format))
    etime = time.mktime(time.strptime(end, time_format))

    ptime = stime + prop * (etime - stime)

    return time.strftime(time_format, time.localtime(ptime))


def random_date(start, end, prop):
    return str_time_prop(start, end, '%Y-%m-%dT%H:%M:%SZ', prop)

product_dict = {"printer": [[
    "Officejet V40 All-in-One Printer, Fax, Scanner, Copier",
    20595,
    "HP",
    "Officejet v40 All-in-One Printer",
    "null",
    "1804",
    "HP Officejet v40 All-in-One Printer",
    "2001-03-01 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/img_1804_medium_1480668091_5028_2323.jpg"
],    [
    "Designjet Z3100ps GP 44-in Photo Printer",
    49995,
    "HP",
    "DesignJet Z3100ps GP 44-in Photo Printer",
    "null",
    "2970697",
    "HP DesignJet Z3100ps GP 44-in Photo Printer large format printer",
    "2007-03-01 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/2970697_5369954127.jpg"
], [
    "Color LaserJet CP2025n 20 ppm A4, 600 x 600 dpi, Network, 2 paper trays",
    19995,
    "HP",
    "Color LaserJet CP2025n Printer",
    "null",
    "1950016",
    "HP LaserJet Color CP2025n Printer Colour 600 x 600 DPI A4",
    "2008-09-01 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/1950016_8732612560.jpg"
], [
    "New  DESKJET 6940DT COLOR INKJET",
    24895,
    "HP",
    "Deskjet 6940dt Printer",
    "null",
    "1200756",
    "HP Deskjet 6940dt Printer inkjet printer",
    "2007-08-10 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/img_1200756_medium_1480991733_6887_5647.jpg"
], [
    "Create effective business documents with the HP LaserJet P1500 Printer Series. Enjoy great performance with a very fast first page out and fast printing speeds up to 23 ppm, plus high quality printing at a price any business can afford.",
    11895,
    "HP",
    "LaserJet P1505n Printer",
    "null",
    "1355233",
    "HP LaserJet P1505n Printer 600 x 600 DPI A4",
    "2008-02-01 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/1355233_4840725599.jpg"
], [
    "Deskjet D2660 Printer, Inkjet, 9ppm, A4",
    11295,
    "HP",
    "Deskjet D2660 Printer",
    "Black",
    "3161192",
    "HP Deskjet D2660 Printer inkjet printer Colour 4800 x 1200 DPI A4",
    "2009-07-01 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/3161192_6336504949.jpg"
],  [
    "The HP Designjet 500 Plus Printer is a versatile large format printer at a great price, delivering outstanding line drawings and photo quality renders with smooth tone transitions. The printer now supports seamless HP-GL/2.",
    32545,
    "HP",
    "Designjet 500 Plus 24-in Roll Printer",
    "null",
    "902138",
    "HP Designjet 500 Plus 24-in Roll Printer large format printer Colour 1200 x 600 DPI 610 x 1067 mm",
    "2007-07-16 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/902138_6200183813.jpg"
],      [
    "Designjet T1120 1118 mm Printer",
    100095,
    "HP",
    "DesignJet T1120 44-in Printer",
    "null",
    "2093198",
    "HP DesignJet T1120 44-in Printer large format printer",
    "2009-04-01 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/2093198_5602046040.jpg"
]], "photo_printer": [[
    "Photosmart A516 Compact Photo Printer 32MB 4800x1200dpi 1.5-in LCD USB 2.0 cardslot PictBridge",
    4495,
    "HP",
    "Photosmart A516 Compact Photo Printer",
    "White",
    "469890",
    "HP Photosmart A516 Compact photo printer Inkjet 4800 x 1200 DPI",
    "2006-08-01 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/469890_8416081207.jpg"
], [
    "Designjet Z3200 44-in Photo Printer",
    559595,
    "HP",
    "Designjet Z3200 44-in Photo Printer",
    "null",
    "1724761",
    "HP Designjet Z3200 44-in Photo Printer large format printer Colour 2400 x 1200 DPI A0 (841 x 1189 mm) Ethernet LAN",
    "2008-10-01 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/1724761_3919339410.jpg"
], [
    "Singlepack Photo Black T606100 220 ml",
    8395,
    "Epson",
    "Singlepack Photo Black T606100 220 ml",
    "null",
    "1231616",
    "Epson Singlepack Photo Black T606100 220 ml",
    "2010-10-01 00:00:00",
    "http://images.icecat.biz/img/gallery_mediums/1231616_7747548995.jpg"
]]}

all_sessions = []
user_ids = [str(x+1) for x in range(20)]
for i in range(100):
    search_query = random.choice(list(product_dict.keys()))
    session = dict()
    session["session_id"] = random.randint(0, 50)
    session["user_id"] = random.choice(user_ids)
    session["timestamp"] = random_date("2022-04-27T18:58:13Z", "2022-02-27T18:58:13Z", random.random())
    session["query"] = search_query
    product_click = random.sample(product_dict[search_query], 1)[0]
    session["product_click"] = product_click[5]
    checkout = dict()
    if random.randint(0, 9) > 6:
        checkout["short_description"] = product_click[0]
        checkout["price"] = product_click[1]
        checkout["supplier"] = product_click[2]
        checkout["name"] = product_click[3]
        checkout["attr_t_product_colour"] = random.choice(["black","white","grey"])
        checkout["id"] = product_click[5]
        checkout["title"] = product_click[6]
        checkout["date_released"] = product_click[7]
        checkout["img_500x500"] = product_click[8]
    session["checkout"] = checkout
    all_sessions.append(session)


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
    for i, doc in enumerate(all_sessions):
            yield doc

print("Indexing documents...")
progress = tqdm.tqdm(unit="docs", total=100)
successes = 0
for ok, action in streaming_bulk(
    client=client, index="session-data", actions=fetch_data(),
):
    progress.update(1)
    successes += ok
print("Indexed %d/%d documents" % (successes, 100))
