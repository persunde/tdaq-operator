# A test program used to test TDAQ Run Operator. Will run until the Webserver says there is a higher run_number than this Pod is "processing data" for
import urllib.request
import sys, os
import json
import time
from random import randrange
  
#arguments_list = sys.argv[1:]
#run_number = arguments_list[0]
run_number = os.environ['RUN_NUMBER']
WEBSERVER_SERVICE_SERVICE_HOST = os.environ['WEBSERVER_SERVICE_SERVICE_HOST']
WEBSERVER_SERVICE_SERVICE_PORT = os.environ['WEBSERVER_SERVICE_SERVICE_PORT']
url = "http://" + WEBSERVER_SERVICE_SERVICE_HOST + ":" + WEBSERVER_SERVICE_SERVICE_PORT + "/?run=" + run_number
  
while True:  
    contents = urllib.request.urlopen(url).read()
    json_content = json.loads(contents)
    if json_content["shutdown"]:
        exit(os.EX_OK)
    # Sleep random time in range [4, 30), increments steps by 2
    sleep_time = randrange(4, 30, 2)
    time.sleep(sleep_time)