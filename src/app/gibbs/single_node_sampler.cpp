
#include "app/gibbs/single_node_sampler.h"

namespace dd{

  void gibbs_single_thread_task(FactorGraph * const _p_fg, int i_worker, 
    int n_worker, bool _sample_evidence){
    SingleThreadSampler sampler = SingleThreadSampler(_p_fg, _sample_evidence);
    sampler.sample(i_worker,n_worker);
  }

  void gibbs_single_thread_sgd_task(FactorGraph * const _p_fg, int i_worker, int n_worker){
    SingleThreadSampler sampler = SingleThreadSampler(_p_fg);
    sampler.sample_sgd(i_worker,n_worker);
  }

  SingleNodeSampler::SingleNodeSampler(FactorGraph * _p_fg, int _nthread, int _nodeid) :
    p_fg (_p_fg), nthread(_nthread), nodeid(_nodeid) {}
  // {
  //   this->nthread = _nthread;
  //   this->nodeid = _nodeid;
    // this->sample_worker = new SingeNodeWorker<FactorGraph, gibbs_single_thread_task>(this->p_fg, 
    //   this->nthread, this->nodeid);
    // this->sgd_worker = new SingeNodeWorker<FactorGraph, gibbs_single_thread_sgd_task>(this->p_fg, 
    //   this->nthread, this->nodeid);
  // }

  SingleNodeSampler::SingleNodeSampler(FactorGraph * _p_fg, int _nthread, int _nodeid,
    bool sample_evidence) :
    p_fg (_p_fg), nthread(_nthread), nodeid(_nodeid), sample_evidence(sample_evidence) {}

  void SingleNodeSampler::clear_variabletally(){
    for(long i=0;i<p_fg->n_var;i++){
      p_fg->infrs->agg_means[i] = 0.0;
      p_fg->infrs->agg_nsamples[i] = 0.0;
    }
    for(long i=0;i<p_fg->infrs->ntallies;i++){
      p_fg->infrs->multinomial_tallies[i] = 0;
    }
  }

  void SingleNodeSampler::sample(){
    numa_run_on_node(this->nodeid);

    this->threads.clear();

    for(int i=0;i<this->nthread;i++){
      this->threads.push_back(std::thread(gibbs_single_thread_task, p_fg, i,
        nthread, sample_evidence));
    }
    // this->sample_worker->execute();
  }

  void SingleNodeSampler::wait(){
    for(int i=0;i<this->nthread;i++){
      this->threads[i].join();
    }
    // this->sample_worker->wait();
  }

  void SingleNodeSampler::sample_sgd(){
    numa_run_on_node(this->nodeid);

    this->threads.clear();

    for(int i=0;i<this->nthread;i++){
      this->threads.push_back(std::thread(gibbs_single_thread_sgd_task, p_fg, i, nthread));
    }
    // this->sgd_worker->execute();
  }

  void SingleNodeSampler::wait_sgd(){
    for(int i=0;i<this->nthread;i++){
      this->threads[i].join();
    }
    // this->sgd_worker->wait();
  }

}





