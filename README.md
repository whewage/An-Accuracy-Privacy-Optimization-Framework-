# An Accuracy-Privacy Optimization Framework Considering User’s Privacy Requirements for Data Stream Mining

This repository contains experimentation with accuracy-privacy framework implemented using a accuracy module,
privacy module and a data fitting module. It consideres user's privacy threshold as a input parameter when
deciding the appropriate accuracy level and vice versa.

## Quickstart

If you have Docker installed, you can run the experiments contained
within this codebase by executing `make jupyter`, opening the returned
URL in a web browser, and executing the contents of the provided
Jupyter notebooks (This has only been tested on an Ubuntu 16.04 host
running Docker 17.05.0-ce).

You will need to run the notebooks in the "dataset-construction"
sub-folder before the notebooks that depend on those datasets. Note
that the results of each experiment are saved to disk to prevent the
need to re-execute the experiments when re-viewing an experiment's
results.

## Dependencies

* Java (>= 1.8.0)
* Leiningen (>= 2.0)

## Running Tests

`make run-tests`

Tests can also be run repeatedly from a Clojure REPL:

1. `lein repl`
2. `(use 'midje.repl)`
3. `(autotest)`

## Further Usage

See Makefile commands
