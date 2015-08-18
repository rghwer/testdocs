package org.nd4j.linalg.indexing;

import com.google.common.primitives.Ints;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.util.ArrayUtil;
import org.nd4j.linalg.api.shape.Shape;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 *
 * Sets up the strides, shape
 * , and offsets
 * for an indexing operation
 *
 * @author Adam Gibson
 */
public class ShapeOffsetResolution implements Serializable {

    private INDArray arr;
    private int[] offsets,shapes,strides;
    private int offset = -1;

    /**
     * Specify the array to use for resolution
     * @param arr the array to use
     *            for resolution
     */
    public ShapeOffsetResolution(INDArray arr) {
        this.arr = arr;
    }

    /**
     * Based on the passed in array
     * compute the shape,offsets, and strides
     * for the given indexes
     * @param indexes the indexes
     *                to compute this based on
     *
     */
    public void exec(INDArrayIndex... indexes) {
        indexes = NDArrayIndex.resolve(arr.shape(),indexes);
        int[] shape = arr.shape();
        int numIntervals = 0;
        //number of new axes dimensions to prepend to the beginning
        int newAxesPrepend = 0;
        //whether we have encountered an all so far
        boolean encounteredAll = false;
        //accumulate the results
        List<Integer> accumShape = new ArrayList<>();
        List<Integer> accumStrides = new ArrayList<>();
        List<Integer> accumOffsets = new ArrayList<>();
        List<Integer> intervalStrides = new ArrayList<>();

        //collect the indexes of the points that get removed
        //for point purposes
        //this will be used to compute the offset
        //for the new array
        List<Integer> pointStrides = new ArrayList<>();
        List<Integer> pointOffsets = new ArrayList<>();
        int numPointIndexes = 0;

        //bump number to read from the shape
        int shapeIndex = 0;
        //stride index to read strides from the array
        int strideIndex = 0;
        //list of indexes to prepend to for new axes
        //if all is encountered
        List<Integer> prependNewAxes = new ArrayList<>();
        for(int i = 0; i < indexes.length; i++) {
            INDArrayIndex idx = indexes[i];
            if (idx instanceof NDArrayIndexAll)
                encounteredAll = true;
            //point: do nothing but move the shape counter
            //also move the stride counter
            if(idx instanceof PointIndex) {
                pointOffsets.add(idx.offset());
                pointStrides.add(arr.stride(strideIndex));
                numPointIndexes++;
                shapeIndex++;
                strideIndex++;
                continue;
            }
            //new axes encountered, need to track whether to prepend or
            //to set the new axis in the middle
            else if(idx instanceof NewAxis) {
                //prepend the new axes at different indexes
                if(encounteredAll) {
                    prependNewAxes.add(i);
                }
                //prepend to the beginning
                //rather than a set index
                else
                    newAxesPrepend++;
                continue;

            }

            //points and intervals both have a direct desired length
            else if(idx instanceof IntervalIndex && !(idx instanceof NDArrayIndexAll) || idx instanceof SpecifiedIndex) {
                if(idx instanceof IntervalIndex) {
                    accumStrides.add(arr.stride(strideIndex) * idx.stride());
                    //used in computing an adjusted offset for the augmented strides
                    intervalStrides.add(idx.stride());
                    if(idx.stride() > 1)
                        numIntervals++;
                }
                else
                    accumStrides.add(arr.stride(strideIndex));
                accumShape.add(idx.length());
                //the stride stays the same
                //add the offset for the index
                if(idx instanceof IntervalIndex) {
                    accumOffsets.add(idx.offset());
                }
                else
                    accumOffsets.add(idx.offset());

                shapeIndex++;
                strideIndex++;
                continue;
            }

            //add the shape and stride
            //based on the original stride/shape

            accumShape.add(shape[shapeIndex++]);
            //account for erroneous strides from dimensions of size 1
            //move the stride index if its one and fill it in at the bottom
            accumStrides.add(arr.stride(strideIndex++));

            //default offsets are zero
            accumOffsets.add(idx.offset());

        }

        //fill in missing strides and shapes
        while(shapeIndex < shape.length) {
            //scalar, should be 1 x 1 rather than the number of columns in the vector
            if(Shape.isVector(shape)) {
                accumShape.add(1);
                shapeIndex++;
            }
            else
                accumShape.add(shape[shapeIndex++]);
        }


        //fill in the rest of the offsets with zero
        int delta = (shape.length <= 2 ? shape.length : shape.length - numPointIndexes);
        boolean needsFilledIn = accumShape.size() != accumStrides.size() && accumOffsets.size() != accumShape.size();
        while(accumOffsets.size() < delta && needsFilledIn)
            accumOffsets.add(0);


        while(accumShape.size() < 2) {
            if(Shape.isRowVectorShape(arr.shape()))
                accumShape.add(0,1);
            else
                accumShape.add(1);
        }
        //prepend for new axes; do this first before
        //doing the indexes to prepend to
        if(newAxesPrepend > 0) {
            for(int i = 0; i < newAxesPrepend; i++) {
                accumShape.add(0, 1);
                //strides for new axis are 0
                accumStrides.add(0,0);
                //prepend offset zero to match the stride and shapes
                accumOffsets.add(0,0);
            }
        }

        /**
         * For each dimension
         * where we want to prepend a dimension
         * we need to add it at the index such that
         * we account for the offset of the number of indexes
         * added up to that point.
         *
         * We do this by doing an offset
         * for each item added "so far"
         *
         * Note that we also have an offset of - 1
         * because we want to prepend to the given index.
         *
         * When prepend new axes for in the middle is triggered
         * i is already > 0
         */
        for(int i = 0; i < prependNewAxes.size(); i++) {
            if(prependNewAxes.get(i) - i >= 0)
                accumShape.add(prependNewAxes.get(i) - i,1);
            else
                accumShape.add(0,1);
            //stride for the new axis is zero
            if(prependNewAxes.get(i) - i >= 0 && prependNewAxes.get(i) - i < prependNewAxes.size())
                accumStrides.add(prependNewAxes.get(i) - i,0);
            else
                prependNewAxes.add(0,0);
        }


        /**
         * Need to post process strides and offsets
         * for trailing ones here
         */
        //prune off extra zeros for trailing and leading ones
        int trailingZeroRemove = accumOffsets.size() - 1;
        while(accumOffsets.size() > accumShape.size()) {
            if(accumOffsets.get(trailingZeroRemove) == 0)
                accumOffsets.remove(accumOffsets.size() - 1);
            trailingZeroRemove--;
        }

        if(accumStrides.size() < accumOffsets.size())
            accumStrides.addAll(pointStrides);
        while(accumOffsets.size() < accumShape.size()) {
            if(Shape.isRowVectorShape(arr.shape()))
                accumOffsets.add(0,0);
            else
                accumOffsets.add(0);
        }
        //finally fill in teh rest of the strides if any are left over
        while(accumStrides.size() < accumOffsets.size()) {
            if(arr.isRowVector())
                accumStrides.add(0,arr.elementStride());
            else
                accumStrides.add(arr.elementStride());
        }




        this.strides = Ints.toArray(accumStrides);
        this.shapes = Ints.toArray(accumShape);
        this.offsets = Ints.toArray(accumOffsets);

        //compute point offsets differently
        /**
         * We need to prepend the strides for the point indexes
         * such that the point index offsets are counted.
         * Note here that we only use point strides
         * when points strides isn't empty.
         * When point strides is empty, this is
         * because a point index was encountered
         * but it was the lead index and therefore should
         * not be counted with the offset.
         *
         *
         * Another thing of note here is that the strides
         * and offsets should line up such that the point
         * and stride match up.
         */
        if(numPointIndexes > 0 && !pointStrides.isEmpty()) {
            //append to the end for tensors
            if(newAxesPrepend >= 1) {
                while(pointStrides.size() < accumOffsets.size()) {
                    pointStrides.add(1);
                }
                //identify in the original accumulate strides
                //where zero was set and emulate the
                //same structure in the point strides
                for(int i = 0; i < accumStrides.size(); i++) {
                    if(accumStrides.get(i) == 0)
                        pointStrides.set(i,0);
                }
            }

            //prepend any missing offsets where relevant for the dot product
            //note here we are using the point offsets and strides
            //for computing the offset
            //the point of a point index is to drop a dimension
            //and index in to a particular offset
            while(pointOffsets.size() < pointStrides.size()) {
                pointOffsets.add(0);
            }
            //special case where offsets aren't caught
            if(arr.isRowVector() && !intervalStrides.isEmpty() && pointOffsets.get(0) == 0)
                this.offset = indexes[1].offset();
            else
                this.offset = ArrayUtil.dotProduct(pointOffsets, pointStrides);
        }
        else if(numIntervals > 0) {

            this.offset = ArrayUtil.dotProduct(accumOffsets,accumStrides) / numIntervals;
        }
        else
            this.offset = ArrayUtil.calcOffset(accumShape,accumOffsets,accumStrides);
    }




    public INDArray getArr() {
        return arr;
    }

    public void setArr(INDArray arr) {
        this.arr = arr;
    }

    public int[] getOffsets() {
        return offsets;
    }

    public void setOffsets(int[] offsets) {
        this.offsets = offsets;
    }

    public int[] getShapes() {
        return shapes;
    }

    public void setShapes(int[] shapes) {
        this.shapes = shapes;
    }

    public int[] getStrides() {
        return strides;
    }

    public void setStrides(int[] strides) {
        this.strides = strides;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }
}
