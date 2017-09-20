/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.nn.layers.feedforward.autoencoder;

import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.activations.ActivationsFactory;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.layers.BasePretrainNetwork;
import org.deeplearning4j.nn.params.PretrainParamInitializer;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.primitives.Pair;

/**
 *  Autoencoder.
 * Add Gaussian noise to input and learn
 * a reconstruction function.
 *
 * @author Adam Gibson
 *
 */
public class AutoEncoder extends BasePretrainNetwork<org.deeplearning4j.nn.conf.layers.AutoEncoder> {

    public AutoEncoder(NeuralNetConfiguration conf) {
        super(conf);
    }

    @Override
    public Pair<INDArray, INDArray> sampleHiddenGivenVisible(INDArray v) {
        setInput(v);
        INDArray ret = encode(v, true);
        return new Pair<>(ret, ret);
    }

    @Override
    public Pair<INDArray, INDArray> sampleVisibleGivenHidden(INDArray h) {
        INDArray ret = decode(h);
        return new Pair<>(ret, ret);
    }

    // Encode
    public INDArray encode(INDArray v, boolean training) {
        INDArray W = getParamWithNoise(PretrainParamInitializer.WEIGHT_KEY, training);
        INDArray hBias = getParamWithNoise(PretrainParamInitializer.BIAS_KEY, training);
        INDArray preAct = v.mmul(W).addiRowVector(hBias);

        //INDArray ret = Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform(conf.getLayer().getActivationFunction(), preAct));
        INDArray ret = layerConf().getActivationFn().getActivation(preAct, training);

        return ret;
    }

    // Decode
    public INDArray decode(INDArray y) {
        INDArray W = getParamWithNoise(PretrainParamInitializer.WEIGHT_KEY, true);
        INDArray vBias = getParamWithNoise(PretrainParamInitializer.VISIBLE_BIAS_KEY, true);
        INDArray preAct = y.mmul(W.transposei()).addiRowVector(vBias);
        //return Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform(conf.getLayer().getActivationFunction(), preAct));
        return layerConf().getActivationFn().getActivation(preAct, true);

    }

    @Override
    public Activations activate(Activations input, boolean training) {
        setInput(input.get(0));
        return ActivationsFactory.getInstance().create(encode(input.get(0), training));
    }

    @Override
    public boolean isPretrainLayer() {
        return true;
    }

    @Override
    public Activations activate(boolean training) {
        return ActivationsFactory.getInstance().create(decode(encode(input.get(0), training)));
    }

    @Override
    public void computeGradientAndScore() {
        INDArray W = getParamWithNoise(PretrainParamInitializer.WEIGHT_KEY, true);

        double corruptionLevel = layerConf().getCorruptionLevel();

        INDArray corruptedX = corruptionLevel > 0 ? getCorruptedInput(input.get(0), corruptionLevel) : input.get(0);
        setInput(corruptedX);

        INDArray y = encode(corruptedX, true);
        INDArray z = decode(y);

        INDArray visibleLoss = input.get(0).sub(z);
        INDArray hiddenLoss = layerConf().getSparsity() == 0 ? visibleLoss.mmul(W).muli(y).muli(y.rsub(1))
                        : visibleLoss.mmul(W).muli(y).muli(y.add(-layerConf().getSparsity()));

        INDArray wGradient = corruptedX.transposei().mmul(hiddenLoss).addi(visibleLoss.transposei().mmul(y));
        INDArray hBiasGradient = hiddenLoss.sum(0);
        INDArray vBiasGradient = visibleLoss.sum(0);

        gradient = createGradient(wGradient, vBiasGradient, hBiasGradient);
        setScoreWithZ(z);

    }

    @Override
    public void fit(Activations data) {
        if(data.getMask(0) != null){
            throw new IllegalArgumentException("Cannot fit with mask array present (not yet implemneted)");
        }
        fit(data.get(0));
    }

    @Override
    public void fit(DataSetIterator iter) {
        while(iter.hasNext()){
            fit(iter.next());
        }
    }

    @Override
    public void fit(INDArray examples, INDArray labels) {
        fit(examples);
    }

    @Override
    public void fit(DataSet data) {
        fit(data.getFeatures());
    }


}