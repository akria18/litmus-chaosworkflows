#!/bin/sh

sleep 200;

kubectl apply -f workflows/cpu-hog.yml
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
