---
layout: default
title: Learning and inference with the statistical model
---

# Learning and inference with the statistical model

For every DeepDive application, [executing any data processing it defines](ops-execution.md) is ultimately to supply with necessary bits in the construction of the [statistical model declared in DDlog](writing-model-ddlog.md) for [*joint inference*](inference.md).
DeepDive provides several commands to streamline operations on the statistical model, including its creation (*grounding*), parameter estimation (*learning*), and computation of probabilities (*inference*) as well as keeping and reusing the parameters of the model (*weights*).

<br><todo>

- grounding factor graphs
    - `deepdive redo variable_id_partition combine_factorgraph`
- learning weights
    - `deepdive do learning`
- performing inference
    - `deepdive do inference`
- reusing weights
    - `deepdive model` command for managing weights and factor graphs
- Migrate whatever piece in <s>[Running an application](running.md)</s>

</todo>

## Grounding the factor graph
*grounding* a 
*factor graph*

The gives rise to a data structure called *factor graph*.

DeepDive uses a factor graph to perform inference.
Therefore, building the graph and writing it to disk is a necessary step for the application to run.
This process is called grounding.
You can perform it by running:

```bash
deepdive do variable_id_partition combine_factorgraph
```

This will generate five files: one for variables, one for factors, one for edges, one for weights, and one for metadata useful to the system. The format of these file is special so that they can be accepted as input by our sampler and will allow the system to use this graph for inference.



## Learning the weights
DeepDive can learn the weights of the factor graph from training data obtained through distant supervision or specified by the user while populating the database during the extraction phase. The main general way for learning the weights is maximum likelihood. To perform the learning operation, run:

```bash
deepdive do learning
```

For convenience, the learned weights can be loaded in the DeepDive database using:

```bash
deepdive do weights
```

This will create a comprehensive view of the weights under `dd_inference_result_weights_mapping` view. You can inspect the weights corresponding to a a given relation by querying this view.



## Inference
After learning the weights, DeepDive can use them and the grounded factor graph to start the inference step and establish the likelihood of a certain relation in an unlabeled example. To do so, run:

```bash
deepdive do inference
```

When running this command, DeepDive will perform marginal inference on the factor graph variables to learn the probabilities of different values they can take over all possible worlds using Gibbs sampling. Using these probabilities, we compute the expectation of a certain relation and store it in the appropriate database table. By convention, DeepDive calls these tables `variable_id_inference_label`, where variable_id is the target relation we are trying to infer.


## Inspecing the result

### Expectation of variables

<todo>write</todo>
Marginal probabilities

### Calibration plots


## Reusing weights

### Keeping learned weights

### Reusing learned weights
