#!/bin/bash

set -euo pipefail

clj -M -e "(compile 'com.stuartsierra.component)"
clj -M -e "(compile 'com.stuartsierra.dependency)"
clj -M -e "(compile 'speakeasy.core)"
clj -M:uberjar --main-class speakeasy.core
