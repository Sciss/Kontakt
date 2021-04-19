#!/bin/bash
cd "$(dirname "$0")"
for ((i = 0 ; i < 10 ; i++)); do
    sleep 5
    ./pump.py
done

