
#!/bin/sh

for each in $(kubectl -nlitmus  get workflows -o jsonpath="{.items[*].metadata.name}");
do
    kubectl -nlitmus delete  workflows $each;
done;

for each in $(kubectl -nlitmus  get chaosresults -o jsonpath="{.items[*].metadata.name}");
do
    kubectl -nlitmus delete chaosresults $each;
done;