"""Example parameter server.
1. Run this.
2. Run dw with "--parameter_server tcp://localhost:8888 --worker_id strongman"
"""

import logging
import msgpack
import zmq
import numpy as np

logging.basicConfig(level=logging.DEBUG,
                    format="%(asctime)s.%(msecs)03d %(levelname)s\t"
                           "%(name)s: %(message)s",
                    datefmt='%FT%T')

PORT = 8888
MAX_EPOCHS = 10


# see dimmwitted.cc DimmWitted::ps_update_weights for client code
def main():
    context = zmq.Context()
    socket = context.socket(zmq.REP)
    socket.bind("tcp://*:%s" % PORT)
    weights = None
    logging.info('parameter server is up now at %s' % PORT)
    while True:
        buf = socket.recv()
        unpacker = msgpack.Unpacker()
        unpacker.feed(buf)
        worker_id, msgtype = next(unpacker), next(unpacker)
        worker_id = worker_id.decode('utf-8')
        msgtype = msgtype.decode('utf-8')
        logging.info('got msg: <%s, %s>, len = %d' % (worker_id, msgtype, len(buf)))

        if msgtype == 'GRADIENTS':
            epochs, delta = next(unpacker), next(unpacker)
            logging.info('\tepochs = %d' % epochs)

            delta = np.frombuffer(delta, dtype=np.float32)
            logging.info('\tgot grads[%d] %s ... %s' % (len(delta), delta[:3], delta[-3:]))

            if weights is None:
                weights = np.zeros(len(delta), dtype=np.float32)
            weights += delta + np.ones(len(delta), dtype=np.float32) / 100

            packer = msgpack.Packer(use_single_float=True, use_bin_type=True)
            command = 'STOP' if epochs >= MAX_EPOCHS else 'CONTINUE'
            bw = np.asarray(weights).tobytes()
            payload = b''.join(map(packer.pack, [command, bw]))
            socket.send(payload)
            logging.info('\tsent wgts[%d] %s ... %s' % (len(weights), weights[:3], weights[-3:]))
        else:
            socket.send(b'ACK')


if __name__ == '__main__':
    main()
