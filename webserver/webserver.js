/**
 * This program is used to tell the K8S Pods to shutdown or not, based on their runNUmber that they send in as a GET param:
 *
 * How to send a GET request:
 *  GET HOST:PORT/?run=<runNumber>
 */

var http = require('http');
let latestRunNumber = 0;
let totalCalls = 0;

const server = http.createServer();
server.on('request', async (req, res) => {
    const newRunNumber = parseInt(req.query.run, 10);
    let shutdown = false;
    if (latestRunNumber > newRunNumber) {
        shutdown = true;
    } else if (latestRunNumber < newRunNumber) {
        latestRunNumber = newRunNumber;
    }

    totalCalls++;

    const response = JSON.stringify({"shutdown": shutdown, "totalCalls": totalCalls});
    res.setHeader('Content-Type', 'application/json');
    res.writeHead(200);
    res.end(response);
});

server.listen(8080);