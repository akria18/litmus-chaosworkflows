#!/bin/sh

kubectl apply -f workflows/pod-delete.yml
until kubectl get workflow  --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1:].metadata.labels.\workflows\.argoproj\.io\/phase}' -nlitmus | grep -m 1 "Succeeded\|Failed";
do
  echo "waiting for the chaos to finish";
done

sleep 10;

for each in $(kubectl get chaosresult -nlitmus --no-headers -oname);
do
    chaosResults=$(kubectl get $each -o jsonpath='{"ExperimentName: "}{.metadata.labels.name}{"; verdict: "}{.status.experimentStatus.verdict}{"; Resilience Score: "}{.status.experimentStatus.probeSuccessPercentage}{" || "}' -nlitmus);
    echo $chaosResults >> report.txt;
done;

# for each in $(kubectl -nlitmus  get workflows -o jsonpath="{.items[*].metadata.name}");
# do
#     kubectl -nlitmus delete  workflows $each;
# done;

# for each in $(kubectl -nlitmus  get chaosresults -o jsonpath="{.items[*].metadata.name}");
# do
#     kubectl -nlitmus delete chaosresults $each;
# done;