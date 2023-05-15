import argparse
import logging

from utbot_executor.listener import PythonExecuteServer


def main(hostname: str, port: int):
    server = PythonExecuteServer(hostname, port)
    server.run()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        prog='UtBot Python Executor',
        description='Listen socket stream and execute function value',
        )
    parser.add_argument('hostname')
    parser.add_argument('port', type=int)
    parser.add_argument('--logfile', default=None)
    parser.add_argument(
            '--loglevel',
            choices=["DEBUG", "INFO", "ERROR"],
            default="ERROR",
            )
    args = parser.parse_args()

    loglevel = {"DEBUG": logging.DEBUG, "INFO": logging.INFO, "ERROR": logging.ERROR}[args.loglevel]
    logging.basicConfig(
            filename=args.logfile,
            format='%(asctime)s | %(levelname)s | %(funcName)s - %(message)s',
            datefmt='%m/%d/%Y %H:%M:%S',
            level=loglevel,
            )
    main(args.hostname, args.port)
