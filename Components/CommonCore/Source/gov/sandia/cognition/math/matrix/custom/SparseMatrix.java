/*
 * File:                SparseMatrix.java
 * Authors:             Jeremy D. Wendt
 * Company:             Sandia National Laboratories
 * Project:             Cognitive Foundry
 *
 * Copyright 2015, Sandia Corporation.  Under the terms of Contract
 * DE-AC04-94AL85000, there is a non-exclusive license for use of this work by
 * or on behalf of the U.S. Government. Export of this program may require a
 * license from the United States Government. See CopyrightHistory.txt for
 * complete details.
 */

package gov.sandia.cognition.math.matrix.custom;

import gov.sandia.cognition.annotation.PublicationReference;
import gov.sandia.cognition.annotation.PublicationType;
import gov.sandia.cognition.math.ComplexNumber;
import gov.sandia.cognition.math.matrix.Matrix;
import gov.sandia.cognition.math.matrix.MatrixEntry;
import gov.sandia.cognition.math.matrix.MatrixFactory;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.util.ArgumentChecker;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Iterator;

/**
 * A sparse matrix implementation. This stores the data in two formats: The
 * first is as an array of sparse vectors for each row. This format is easy to
 * modify (see the set* methods), but slow to operate on due to each element
 * being dispersed to different locations in memory. The second is the
 * compressed Yale format (see
 * http://en.wikipedia.org/wiki/Sparse_matrix#Yale_format). This format densely
 * packs the values into arrays of double -- permitting fast computation, but
 * difficult and costly modification. This switches between the formats as
 * necessary (to Yale when multiplying, to sparse vector when altering), so it
 * is recommended that computation calls not be interleaved with modification
 * calls unnecessarily.
 *
 * @author Jeremy D. Wendt
 * @since   3.4.3
 */
@PublicationReference(author = "Wikipedia",
    title = "Sparse Matrix / Yale format",
    type = PublicationType.WebPage,
    year = 2013,
    url = "http://en.wikipedia.org/wiki/Sparse_matrix#Yale_format")
public class SparseMatrix
    extends BaseMatrix
{

    /**
     * The number of rows in the matrix. This is stored explicitly here (vs.
     * using the implicit storage in the sparse rows or compressed Yale format)
     * because of the fact that this switches between compressed Yale and sparse
     * row formats at runtime.
     */
    private int numRows;

    /**
     * The number of columns in the matrix. This is stored explicitly here (vs.
     * using the implicit storage in the sparse rows) because of the fact that
     * this switches between compressed Yale (which doesn't store the number of
     * columns anywhere) and sparse row formats at runtime.
     */
    private int numCols;

    /**
     * The sparse vector representation of the matrix. Can be out-of-sync with
     * latest values (or null) depending on if the compressed Yale format is the
     * current representation. See the isCompresed method to tell which format
     * is current.
     *
     */
    private SparseVector[] rows;

    /**
     * Part of the compressed Yale format. Generally only stores the non-zero
     * elements of the matrix (to optimize some of the methods herein, some zero
     * elements may be in vals). Will be null when the matrix is not compressed.
     */
    protected double[] values;

    /**
     * Part of the compressed Yale format. Stores the first index into vals for
     * each row in the matrix. Has length of numRows + 1. Will be null when the
     * matrix is not compressed.
     */
    protected int[] firstIndicesForRows;

    /**
     * Part of the compressed Yale format. Has the same length of vals. This
     * specifies which column each element of vals is in. Will be null when the
     * matrix is not compressed.
     */
    protected int[] columnIndices;

    /**
     * This method is provided so that the calling programmer can explicitly
     * declare when a matrix should be compressed to the compressed Yale format.
     * Note that the method will be automatically compressed and decompressed
     * when various methods are called (e.g., decompressed for set* methods,
     * compressed for times, dotTimes, etc.).
     */
    final public void compress()
    {
        if (isCompressed())
        {
            return;
        }

        final int numRows = this.getNumRows();
        int nonZeroCount = 0;
        for (int i = 0; i < numRows; ++i)
        {
            rows[i].compress();
            nonZeroCount += rows[i].getIndices().length;
        }
        values = new double[nonZeroCount];
        firstIndicesForRows = new int[numRows + 1];
        columnIndices = new int[nonZeroCount];
        int curIdx = 0;
        for (int i = 0; i < numRows; ++i)
        {
            firstIndicesForRows[i] = curIdx;
            for (int j = 0; j < rows[i].getIndices().length; ++j)
            {
                values[curIdx] = rows[i].getValues()[j];
                columnIndices[curIdx] = rows[i].getIndices()[j];
                ++curIdx;
            }
            rows[i].clear();
        }
        firstIndicesForRows[numRows] = curIdx;
    }

    /**
     * This method is provided so that the calling programmer can explicitly
     * declare when a matrix should be decompressed from the compressed Yale
     * format. Note that the method will be automatically compressed and
     * decompressed when various methods are called (e.g., decompressed for set*
     * methods, compressed for times, dotTimes, etc.).
     */
    final public void decompress()
    {
        if (!isCompressed())
        {
            return;
        }

        for (int i = 0; i < getNumRows(); ++i)
        {
            rows[i].clear();
            for (int j = firstIndicesForRows[i]; j < firstIndicesForRows[i + 1]; ++j)
            {
                rows[i].setElement(columnIndices[j], values[j]);
            }
        }
        values = null;
        firstIndicesForRows = columnIndices = null;
    }

    /**
     * This method tests if the matrix is currently compressed to the compressed
     * Yale format. Please note that the matrix gets compressed and decompressed
     * as a side effect of various operations (including decompressed by set*
     * calls and compressed by times, etc.). Therefore, don't be surprised if
     * you call decompress, call some other methods and then find that it's
     * compressed.
     *
     * @return true if this is compressed to the compressed Yale format, else
     * false.
     */
    final public boolean isCompressed()
    {
        return (values != null) && (firstIndicesForRows != null) && (columnIndices != null);
    }

    /**
     * Package-private method that returns the compressed Yale-format values.
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * @return the compressed Yale-format values
     */
    final double[] getValues()
    {
        if (!isCompressed())
        {
            compress();
        }
        return values;
    }

    /**
     * Package-private method that returns the compressed Yale-format column
     * indices.
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * @return the compressed Yale-format column indices
     */
    final int[] getColumnIndices()
    {
        if (!isCompressed())
        {
            compress();
        }
        return columnIndices;
    }

    /**
     * Package-private method that returns the compressed Yale-format first
     * indices for each row.
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * @return the compressed Yale-format first indices for each row
     */
    final int[] getFirstInRows()
    {
        if (!isCompressed())
        {
            compress();
        }
        return firstIndicesForRows;
    }

    /**
     * Creates a new sparse matrix with the specified number of rows and
     * columns. All values are implicitly set to zero.
     *
     * NOTE: Upon completion this is in the sparse vector.
     *
     * @param numRows The number of rows in the matrix
     * @param numCols The number of columns in the matrix
     */
    public SparseMatrix(
        final int numRows,
        final int numCols)
    {
        this.numCols = numCols;
        this.numRows = numRows;
        rows = new SparseVector[numRows];
        for (int i = 0; i < numRows; ++i)
        {
            rows[i] = new SparseVector(numCols);
        }
        values = null;
        firstIndicesForRows = columnIndices = null;
    }

    /**
     * Creates a new sparse matrix with the same dimensions and data as m
     * (performs a deep copy).
     *
     * NOTE: Upon completion this is in the same format as m.
     *
     * @param m The sparse matrix to copy
     */
    public SparseMatrix(
        final SparseMatrix m)
    {
        this.numCols = m.getNumColumns();
        this.numRows = m.getNumRows();
        rows = new SparseVector[m.rows.length];
        if (m.isCompressed())
        {
            for (int i = 0; i < this.numRows; ++i)
            {
                rows[i] = new SparseVector(m.numCols);
            }
            values = Arrays.copyOf(m.values, m.values.length);
            firstIndicesForRows = Arrays.copyOf(m.firstIndicesForRows,
                m.firstIndicesForRows.length);
            columnIndices = Arrays.copyOf(m.columnIndices, m.columnIndices.length);
        }
        else
        {
            for (int i = 0; i < this.numRows; ++i)
            {
                rows[i] = new SparseVector(m.rows[i]);
            }
            values = null;
            firstIndicesForRows = columnIndices = null;
        }
    }

    /**
     * Creates a new sparse matrix with the same dimensions and data as d
     * (performs a deep copy).
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * @param d The dense matrix to copy
     */
    public SparseMatrix(
        final DenseMatrix d)
    {
        int nnz = d.getNonZeroCount();
        this.numCols = d.getNumColumns();
        this.numRows = d.getNumRows();
        rows = new SparseVector[numRows];
        values = new double[nnz];
        firstIndicesForRows = new int[numRows + 1];
        columnIndices = new int[nnz];
        int idx = 0;
        for (int i = 0; i < numRows; ++i)
        {
            rows[i] = new SparseVector(numCols);
            firstIndicesForRows[i] = idx;
            for (int j = 0; j < numCols; ++j)
            {
                double val = d.row(i).values[j];
                if (val != 0)
                {
                    values[idx] = val;
                    columnIndices[idx] = j;
                    ++idx;
                }
            }
        }
        firstIndicesForRows[numRows] = idx;
    }

    /**
     * Creates a new sparse matrix with the same dimensions and data as d
     * (performs a deep copy).
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * @param d The diagonal matrix to copy
     */
    public SparseMatrix(
        final DiagonalMatrix d)
    {
        this.numCols = d.getNumColumns();
        this.numRows = d.getNumRows();
        int nnz = 0;
        for (int i = 0; i < this.numRows; ++i)
        {
            if (d.get(i, i) != 0)
            {
                ++nnz;
            }
        }
        rows = new SparseVector[numRows];
        values = new double[nnz];
        firstIndicesForRows = new int[numRows + 1];
        columnIndices = new int[nnz];
        int idx = 0;
        for (int i = 0; i < numRows; ++i)
        {
            rows[i] = new SparseVector(numCols);
            firstIndicesForRows[i] = idx;
            double val = d.get(i, i);
            if (val != 0)
            {
                values[idx] = val;
                columnIndices[idx] = i;
                ++idx;
            }
        }

        firstIndicesForRows[numRows] = idx;
    }

    /**
     * Package-private helper that creates a completely empty matrix (neither
     * format initialized). It is assumed that the calling function will
     * immediately fill the appropriate values.
     *
     * @param numRows The number of rows in the matrix
     * @param numCols The number of columns in the matrix
     * @param unused Only present to differentiate from the other full
     * constructor
     */
    SparseMatrix(
        final int numRows,
        final int numCols,
        final boolean unused)
    {
        this.numCols = numCols;
        this.numRows = numRows;
        rows = new SparseVector[numRows];
        values = null;
        firstIndicesForRows = columnIndices = null;
        // Don't initialize the rows' values
    }

    /**
     * This should never be called by anything or anyone other than Java's
     * serialization code.
     */
    protected SparseMatrix()
    {
        // NOTE: This initialize to bad values or nothing
        numCols = numRows = 0;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: This does not affect this's format. The cloned matrix is in the
     * same format as this.
     * @return {@inheritDoc}
     */
    @Override
    public Matrix clone()
    {
        SparseMatrix clone = (SparseMatrix) super.clone();
        
        clone.numCols = this.getNumColumns();
        clone.numRows = this.getNumRows();
        clone.rows = new SparseVector[this.rows.length];
        if (this.isCompressed())
        {
            for (int i = 0; i < this.numRows; ++i)
            {
                clone.rows[i] = new SparseVector(this.numCols);
            }
            clone.values = Arrays.copyOf(this.values, this.values.length);
            clone.firstIndicesForRows = Arrays.copyOf(this.firstIndicesForRows,
                this.firstIndicesForRows.length);
            clone.columnIndices = Arrays.copyOf(this.columnIndices, this.columnIndices.length);
        }
        else
        {
            for (int i = 0; i < this.numRows; ++i)
            {
                clone.rows[i] = new SparseVector(this.rows[i]);
            }
            clone.values = null;
            clone.firstIndicesForRows = columnIndices = null;
        }
        
        return clone;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: This does not affect this's format.
     * @return {@inheritDoc}
     */
    @Override
    public boolean isSparse()
    {
        return true;
    }

    @Override
    public int getEntryCount()
    {
        if (!this.isCompressed())
        {
            this.compress();
        }
        
        return this.values.length;
     }

    /**
     * This enum allows the getNumNonZeroWhenCombinedWith method to count the
     * number of non-zeros depending on if an add/subtract (OR) or multiply
     * (AND) operation is being performed.
     */
    static enum Combiner
    {

        /**
         * For operations that are 0 unless both operands are non-zero (mult)
         */
        AND,
        /**
         * For operations that are 0 only if both operands are zero (add,
         * subtract)
         */
        OR;

    };

    /**
     * Helper method that calculates the number of non-zero entries in the
     * result of a computation between two sparse matrices. Notice that the math
     * is not actually performed, so zeros caused by the operation (say A - A)
     * are not counted.
     *
     * NOTE: This method assumes this is in the compressed Yale format on start
     * and does not change the format.
     *
     * @param otherColIds The other sparse matrix's compressed Yale format
     * column ids
     * @param otherFirstInRows The other matrix's compressed Yale format first
     * in rows
     * @param combiner How zeros are formed by the type of operation to be
     * performed (AND = times, OR = plus/minus)
     * @return The number of non-zero elements expected by the operation and the
     * location of the elements.
     */
    private int getNumNonZeroWhenCombinedWith(
        final int[] otherColIds,
        final int[] otherFirstInRows,
        final Combiner combiner)
    {
        int nnz = 0;
        int myidx, otheridx;
        // This assumes none of the entries combine together to 0
        for (int i = 0; i < this.numRows; ++i)
        {
            // Counters for me and other on this row
            myidx = firstIndicesForRows[i];
            otheridx = otherFirstInRows[i];
            // While we're both on this row
            while (myidx < firstIndicesForRows[i + 1] && otheridx
                < otherFirstInRows[i + 1])
            {
                // If we share the spot, count it as one and advance both
                if (columnIndices[myidx] == otherColIds[otheridx])
                {
                    ++nnz;
                    ++myidx;
                    ++otheridx;
                }
                // Otherwise if I'm before other on this row, count me and
                // advance me
                else if (columnIndices[myidx] < otherColIds[otheridx])
                {
                    if (combiner == Combiner.OR)
                    {
                        ++nnz;
                    }
                    ++myidx;
                }
                // Otherwise he's first, count him and advance
                else
                {
                    if (combiner == Combiner.OR)
                    {
                        ++nnz;
                    }
                    ++otheridx;
                }
            }
            if (combiner == Combiner.OR)
            {
                // If I've made it here, one of us could still be on the row while
                // the other isn't.  Count each non-zero and advance
                while (myidx < firstIndicesForRows[i + 1])
                {
                    ++nnz;
                    ++myidx;
                }
                while (otheridx < otherFirstInRows[i + 1])
                {
                    ++nnz;
                    ++otheridx;
                }
            }
        }

        return nnz;
    }

    /**
     * This helper combines the logic for all addition-style operations (so
     * minus becomes plusEqualsScaled with a scaleOtherBy of -1).
     *
     * NOTE: This method assumes this and other are in the compressed Yale
     * format, and they remain in that state after completion.
     *
     * @param colIdxsOut [OUT] The resulting column indices
     * @param firstInRowsOut [OUT] The resulting first idx in rows values
     * @param valsOut [OUT] The resulting values
     * @param other The other matrix
     * @param scaleOtherBy The scalar to multiply the other's values by before
     * summing.
     */
    private void plusEqualsScaled(
        final int[] colIdxsOut,
        final int[] firstInRowsOut,
        final double[] valsOut,
        final SparseMatrix other,
        final double scaleOtherBy)
    {
        int newidx = 0;
        int myctr, otherctr;
        // For all rows
        for (int i = 0; i < this.numRows; ++i)
        {
            // The first index for the row is the current idx
            firstInRowsOut[i] = newidx;

            // Now set up both of us for this row
            myctr = firstIndicesForRows[i];
            otherctr = other.firstIndicesForRows[i];
            // As long as we're both still on the row...
            while (myctr < firstIndicesForRows[i + 1] && otherctr
                < other.firstIndicesForRows[i + 1])
            {
                // Three cases:
                // 1) We're both at the same point, store our sum
                if (columnIndices[myctr] == other.columnIndices[otherctr])
                {
                    valsOut[newidx] = values[myctr] + (other.values[otherctr]
                        * scaleOtherBy);
                    colIdxsOut[newidx] = columnIndices[myctr];
                    ++newidx;
                    ++myctr;
                    ++otherctr;
                }
                // 2) Me first, store me
                else if (columnIndices[myctr] < other.columnIndices[otherctr])
                {
                    valsOut[newidx] = values[myctr];
                    colIdxsOut[newidx] = columnIndices[myctr];
                    ++newidx;
                    ++myctr;
                }
                // 3) Him first, store him
                else
                {
                    valsOut[newidx] = (other.values[otherctr] * scaleOtherBy);
                    colIdxsOut[newidx] = other.columnIndices[otherctr];
                    ++newidx;
                    ++otherctr;
                }
            }
            // One of these while loops could still run
            while (myctr < firstIndicesForRows[i + 1])
            {
                valsOut[newidx] = values[myctr];
                colIdxsOut[newidx] = columnIndices[myctr];
                ++newidx;
                ++myctr;
            }
            while (otherctr < other.firstIndicesForRows[i + 1])
            {
                valsOut[newidx] = (other.values[otherctr] * scaleOtherBy);
                colIdxsOut[newidx] = other.columnIndices[otherctr];
                ++newidx;
                ++otherctr;
            }
        }
        firstInRowsOut[numRows] = newidx;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    public void scaledPlusEquals(
        final SparseMatrix other,
        final double scaleFactor)
    {
        this.assertSameDimensions(other);
        if (!isCompressed())
        {
            compress();
        }
        if (!other.isCompressed())
        {
            other.compress();
        }
        // First get the number of non-zero entries in the output
        int nnzAfter = getNumNonZeroWhenCombinedWith(other.columnIndices,
            other.firstIndicesForRows, Combiner.OR);

        // Now create the output matrix's values (init as empty but to nnz size)
        double[] newVals = new double[nnzAfter];
        int[] newColIdxs = new int[nnzAfter];
        int[] newFirstIdxsForRows = new int[getNumRows() + 1];

        // Now calculate the result of summing thes matrices
        plusEqualsScaled(newColIdxs, newFirstIdxsForRows, newVals, other,
            scaleFactor);

        // Now store the new values in me
        values = newVals;
        columnIndices = newColIdxs;
        firstIndicesForRows = newFirstIdxsForRows;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    public void scaledPlusEquals(
        final DenseMatrix other,
        final double scaleFactor)
    {
        this.assertSameDimensions(other);
        
        // Such a bad idea!  This will store dense values in a sparse matrix
        // Take care of the null case -- keeps from an out-of-bounds exception below
        if (getNumRows() == 0)
        {
            return;
        }
        if (!isCompressed())
        {
            compress();
        }
        // NOTE: I'm doing something sneaky here.  I'm storing the old values
        // in the compressed version while storing the updated rows in the
        // decompressed rows.
        int rowNum = 0;
        SparseVector row = new SparseVector(other.row(rowNum));
        row.scaleEquals(scaleFactor);
        for (int i = 0; i < values.length; ++i)
        {
            while (i >= firstIndicesForRows[rowNum + 1])
            {
                rows[rowNum] = row;
                ++rowNum;
                row = new SparseVector(other.row(rowNum));
                row.scaleEquals(scaleFactor);
            }
            row.setElement(columnIndices[i], row.get(columnIndices[i]) + values[i]);
        }
        while (rowNum < this.numRows)
        {
            rows[rowNum] = row;
            ++rowNum;
            if (rowNum >= this.numRows)
            {
                break;
            }
            row = new SparseVector(other.row(rowNum));
            row.scaleEquals(scaleFactor);
        }
        values = null;
        columnIndices = null;
        firstIndicesForRows = null;
        compress();
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    public void scaledPlusEquals(
        final DiagonalMatrix other,
        final double scaleFactor)
    {
        this.assertSameDimensions(other);
        if (!isCompressed())
        {
            compress();
        }
        // Count the number of new values to add.
        int nnz = getNumNonZeroWhenCombinedWith(getZeroToNMinusOneArray(
            other.getNumRows()), getZeroToNMinusOneArray(other.getNumRows() + 1),
            Combiner.OR);

        // Now start up the values for the sum matrix
        double[] newVals = new double[nnz];
        int[] newFirstIdxsForRows = new int[firstIndicesForRows.length];
        int[] newColIdxs = new int[nnz];

        // Now do the logic for adding
        plusEqualsScaled(newColIdxs, newFirstIdxsForRows, newVals, other, 1);

        // Now store the new values in me
        values = newVals;
        columnIndices = newColIdxs;
        firstIndicesForRows = newFirstIdxsForRows;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    public final void plusEquals(
        final SparseMatrix other)
    {
        this.assertSameDimensions(other);
        if (!isCompressed())
        {
            compress();
        }
        if (!other.isCompressed())
        {
            other.compress();
        }
        // First get the number of non-zero entries in the output
        int nnzAfter = getNumNonZeroWhenCombinedWith(other.columnIndices,
            other.firstIndicesForRows, Combiner.OR);

        // Now create the output matrix's values (init as empty but to nnz size)
        double[] newVals = new double[nnzAfter];
        int[] newColIdxs = new int[nnzAfter];
        int[] newFirstIdxsForRows = new int[getNumRows() + 1];

        // Now calculate the result of summing thes matrices
        plusEqualsScaled(newColIdxs, newFirstIdxsForRows, newVals, other, 1);

        // Now store the new values in me
        values = newVals;
        columnIndices = newColIdxs;
        firstIndicesForRows = newFirstIdxsForRows;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    public final void plusEquals(
        final DenseMatrix other)
    {
        this.assertSameDimensions(other);
        
        // Such a bad idea!  This will store dense values in a sparse matrix
        // Take care of the null case -- keeps from an out-of-bounds exception below
        if (getNumRows() == 0)
        {
            return;
        }
        if (!isCompressed())
        {
            compress();
        }
        // NOTE: I'm doing something sneaky here.  I'm storing the old values
        // in the compressed version while storing the updated rows in the
        // decompressed rows.
        int rowNum = 0;
        SparseVector row = new SparseVector(other.row(rowNum));
        for (int i = 0; i < values.length; ++i)
        {
            while (i >= firstIndicesForRows[rowNum + 1])
            {
                rows[rowNum] = row;
                ++rowNum;
                row = new SparseVector(other.row(rowNum));
            }
            row.setElement(columnIndices[i], row.get(columnIndices[i]) + values[i]);
        }
        while (rowNum < this.numRows)
        {
            rows[rowNum] = row;
            ++rowNum;
            if (rowNum >= this.numRows)
            {
                break;
            }
            row = new SparseVector(other.row(rowNum));
        }
        values = null;
        columnIndices = null;
        firstIndicesForRows = null;
        compress();
    }

    /**
     * Private helper that generates an array of integers from 0 to n-1
     * (inclusive).
     *
     * NOTE: Does not affect internal storage format.
     *
     * @param n The length of the array desired
     * @return The above-described array
     */
    private static int[] getZeroToNMinusOneArray(
        final int n)
    {
        int[] result = new int[n];
        for (int i = 0; i < n; ++i)
        {
            result[i] = i;
        }

        return result;
    }

    /**
     * Private helper for adding and subtracting from a diagonal matrix.
     *
     * This method assumes this is in compressed Yale format and does not alter
     * that.
     *
     * @param colIdxsOut [OUT] The compressed Yale-format column indices for the
     * output
     * @param firstInRowsOut [OUT] The compressed Yale-format first index into
     * vals for all rows
     * @param valsOut [OUT] The compressed Yale-format values
     * @param other The diagonal matrix to scale then sum with this
     * @param scaleOtherBy The amount to scale other by (usually +/- 1)
     */
    private void plusEqualsScaled(
        final int[] colIdxsOut,
        final int[] firstInRowsOut,
        final double[] valsOut,
        final DiagonalMatrix other,
        final double scaleOtherBy)
    {
        int rowNum = 0;
        int idx = 0;
        boolean addedOnRow = false;
        firstInRowsOut[rowNum] = idx;
        for (int i = 0; i < values.length; ++i)
        {
            // This skips all-zero rows and advances this row when necessary
            while (i >= firstIndicesForRows[rowNum + 1])
            {
                // But we have to add an element for the diagonal element in 
                // the other matrix
                if (!addedOnRow)
                {
                    valsOut[idx] = (other.get(rowNum, rowNum)
                        * scaleOtherBy);
                    colIdxsOut[idx] = rowNum;
                    ++idx;
                }
                // Now advance the row information
                ++rowNum;
                addedOnRow = false;
                firstInRowsOut[rowNum] = idx;
            }
            // If we're on the diagonal
            if (rowNum == columnIndices[i])
            {
                // Add the current value with the other guy's diagoal
                valsOut[idx] = (other.get(rowNum, rowNum) * scaleOtherBy)
                    + values[i];
                addedOnRow = true;
            }
            // Otherwise, if I'm past the diagonal and haven't added the other
            // guy's value
            else if (columnIndices[i] > rowNum && !addedOnRow)
            {
                // Add the diagonal
                colIdxsOut[idx] = rowNum;
                valsOut[idx] = (other.get(rowNum, rowNum) * scaleOtherBy);
                addedOnRow = true;
                // Then add the current value
                ++idx;
                valsOut[idx] = values[i];
            }
            // Otherwise, I'm before the diagonal or I've already added it
            else
            {
                // So add this value
                valsOut[idx] = values[i];
            }
            colIdxsOut[idx] = columnIndices[i];
            ++idx;
        }
        // Now add elements for diagonals past the last non-zero row in this
        while (rowNum < numRows)
        {
            if (!addedOnRow)
            {
                valsOut[idx] = (other.get(rowNum, rowNum) * scaleOtherBy);
                colIdxsOut[idx] = rowNum;
                ++idx;
            }
            ++rowNum;
            firstInRowsOut[rowNum] = idx;
        }
        firstInRowsOut[rowNum] = idx;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    public final void plusEquals(
        final DiagonalMatrix other)
    {
        this.assertSameDimensions(other);
        if (!isCompressed())
        {
            compress();
        }
        // Count the number of new values to add.
        int nnz = getNumNonZeroWhenCombinedWith(getZeroToNMinusOneArray(
            other.getNumRows()), getZeroToNMinusOneArray(other.getNumRows() + 1),
            Combiner.OR);

        // Now start up the values for the sum matrix
        double[] newVals = new double[nnz];
        int[] newFirstIdxsForRows = new int[firstIndicesForRows.length];
        int[] newColIdxs = new int[nnz];

        // Now do the logic for adding
        plusEqualsScaled(newColIdxs, newFirstIdxsForRows, newVals, other, 1);

        // Now store the new values in me
        values = newVals;
        columnIndices = newColIdxs;
        firstIndicesForRows = newFirstIdxsForRows;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this and other are in the compressed Yale format.
     */
    @Override
    public final void minusEquals(
        final SparseMatrix other)
    {
        this.assertSameDimensions(other);
        if (!isCompressed())
        {
            compress();
        }
        if (!other.isCompressed())
        {
            other.compress();
        }

        // Calculate the resulting number of non-zero elements
        int nnzAfter = getNumNonZeroWhenCombinedWith(other.columnIndices,
            other.firstIndicesForRows, Combiner.OR);

        // Create the new locations for the results
        double[] newVals = new double[nnzAfter];
        int[] newColIdxs = new int[nnzAfter];
        int[] newFirstIdxsForRows = new int[getNumRows() + 1];

        // Actually perform the subtraction
        plusEqualsScaled(newColIdxs, newFirstIdxsForRows, newVals, other, -1);

        // Store the results
        values = newVals;
        columnIndices = newColIdxs;
        firstIndicesForRows = newFirstIdxsForRows;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    public final void minusEquals(
        final DenseMatrix other)
    {
        this.assertSameDimensions(other);
        
        // Such a bad idea!  This will store dense values in a sparse matrix
        // Take care of the null case -- keeps from an out-of-bounds exception below
        if (getNumRows() == 0)
        {
            return;
        }
        if (!isCompressed())
        {
            compress();
        }
        // NOTE: I'm storing the old values in the compressed locations and the
        // result in the sparse vector rows.
        int rowNum = 0;
        SparseVector row = new SparseVector(other.row(rowNum));
        row.negativeEquals();
        for (int i = 0; i < values.length; ++i)
        {
            while (i >= firstIndicesForRows[rowNum + 1])
            {
                rows[rowNum] = row;
                ++rowNum;
                row = new SparseVector(other.row(rowNum));
                row.negativeEquals();
            }
            row.setElement(columnIndices[i], row.get(columnIndices[i]) + values[i]);
        }
        while (rowNum < this.numRows)
        {
            rows[rowNum] = row;
            ++rowNum;
            if (rowNum >= this.numRows)
            {
                break;
            }
            row = new SparseVector(other.row(rowNum));
            row.negativeEquals();
        }
        values = null;
        firstIndicesForRows = columnIndices = null;
        compress();
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    public final void minusEquals(
        final DiagonalMatrix other)
    {
        this.assertSameDimensions(other);
        
        if (!isCompressed())
        {
            compress();
        }
        // Count the number of new values to add.
        int nnz = getNumNonZeroWhenCombinedWith(getZeroToNMinusOneArray(
            other.getNumRows()), getZeroToNMinusOneArray(other.getNumRows() + 1),
            Combiner.OR);

        // Initialize the new matrix storage
        double[] newVals = new double[nnz];
        int[] newFirstIdxsForRows = new int[firstIndicesForRows.length];
        int[] newColIdxs = new int[nnz];

        // Actually do the computation
        plusEqualsScaled(newColIdxs, newFirstIdxsForRows, newVals, other, -1);

        // Store the results
        values = newVals;
        columnIndices = newColIdxs;
        firstIndicesForRows = newFirstIdxsForRows;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this and other are in the compressed Yale format.
     */
    @Override
    public final void dotTimesEquals(
        final SparseMatrix other)
    {
        this.assertSameDimensions(other);
        
        if (!isCompressed())
        {
            compress();
        }
        if (!other.isCompressed())
        {
            other.compress();
        }

        // Count the number of new values to add.
        int nnz = getNumNonZeroWhenCombinedWith(other.columnIndices,
            other.firstIndicesForRows, Combiner.AND);

        // Initialize the new matrix storage
        double[] newVals = new double[nnz];
        int[] newFirstIdxsForRows = new int[firstIndicesForRows.length];
        int[] newColIdxs = new int[nnz];

        // Do the computation
        int newidx = 0;
        int myctr, otherctr;
        // For all rows
        for (int i = 0; i < this.numRows; ++i)
        {
            // The first index for the row is the current idx
            newFirstIdxsForRows[i] = newidx;

            // Now set up both of us for this row
            myctr = firstIndicesForRows[i];
            otherctr = other.firstIndicesForRows[i];
            // As long as we're both still on the row...
            while (myctr < firstIndicesForRows[i + 1] && otherctr
                < other.firstIndicesForRows[i + 1])
            {
                // Three cases:
                // 1) We're both at the same point, store our product
                if (columnIndices[myctr] == other.columnIndices[otherctr])
                {
                    newVals[newidx] = values[myctr] * other.values[otherctr];
                    newColIdxs[newidx] = columnIndices[myctr];
                    ++newidx;
                    ++myctr;
                    ++otherctr;
                }
                // 2) Me first, advance me
                else if (columnIndices[myctr] < other.columnIndices[otherctr])
                {
                    ++myctr;
                }
                // 3) Him first, advance him
                else
                {
                    ++otherctr;
                }
            }
        }
        newFirstIdxsForRows[numRows] = newidx;

        // Now store the results
        values = newVals;
        firstIndicesForRows = newFirstIdxsForRows;
        columnIndices = newColIdxs;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    public final void dotTimesEquals(
        final DenseMatrix other)
    {
        this.assertSameDimensions(other);
        if (!isCompressed())
        {
            compress();
        }
        // The shortcut I'll take here is that few of the dense matrix's values
        // are zero where I'm not
        int rownum = 0;
        for (int i = 0; i < values.length; ++i)
        {
            while (i >= firstIndicesForRows[rownum + 1])
            {
                ++rownum;
            }
            values[i] *= other.get(rownum, columnIndices[i]);
        }
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    public final void dotTimesEquals(
        final DiagonalMatrix other)
    {
        this.assertSameDimensions(other);
        if (!isCompressed())
        {
            compress();
        }
        // Count the number of new values to add.
        int nnz = getNumNonZeroWhenCombinedWith(getZeroToNMinusOneArray(
            other.getNumRows()), getZeroToNMinusOneArray(other.getNumRows() + 1),
            Combiner.AND);

        // Initialize the new matrix storage
        double[] newVals = new double[nnz];
        int[] newFirstIdxsForRows = new int[firstIndicesForRows.length];
        int[] newColIdxs = new int[nnz];

        // Actually do the computation
        int rowNum = 0;
        int idx = 0;
        boolean addedOnRow = false;
        newFirstIdxsForRows[rowNum] = idx;
        for (int i = 0; i < values.length; ++i)
        {
            while (i >= firstIndicesForRows[rowNum + 1])
            {
                ++rowNum;
                newFirstIdxsForRows[rowNum] = idx;
            }
            if (rowNum == columnIndices[i])
            {
                newVals[idx] = values[i] * other.get(rowNum, rowNum);
                newColIdxs[idx] = rowNum;
                ++idx;
            }
        }
        newFirstIdxsForRows[rowNum] = idx;

        // Store the results
        values = newVals;
        columnIndices = newColIdxs;
        firstIndicesForRows = newFirstIdxsForRows;
    }

    /**
     * {@inheritDoc}
     *
     * This returns either a dense or a sparse matrix depending on the
     * sparseness of the resulting multiplication
     *
     * NOTE: Upon completion this and other are in the compressed Yale format.
     * @return {@inheritDoc}
     */
    @Override
    public final Matrix times(
        final SparseMatrix other)
    {
        this.assertMultiplicationDimensions(other);
        if (!isCompressed())
        {
            compress();
        }
        if (!other.isCompressed())
        {
            other.compress();
        }
        
        final DenseVector[] rows = new DenseVector[this.numRows];
        for (int i = 0; i < this.numRows; ++i)
        {
            DenseVector row = new DenseVector(other.getNumColumns());
            for (int j = firstIndicesForRows[i]; j < firstIndicesForRows[i + 1]; ++j)
            {
                for (int k = other.firstIndicesForRows[columnIndices[j]]; k
                    < other.firstIndicesForRows[columnIndices[j] + 1]; ++k)
                {
                    row.values[other.columnIndices[k]] += other.values[k] * values[j];
                }
            }
            rows[i] = row;
        }
        
        final DenseMatrix result = new DenseMatrix(rows);

        if (result.getNonZeroPercent() < SparseVector.SPARSE_TO_DENSE_THRESHOLD)
        {
            return new SparseMatrix(result);
        }
        else
        {
            return result;
        }
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * @return {@inheritDoc}
     */
    @Override
    public final Matrix times(
        final DenseMatrix other)
    {
        this.assertMultiplicationDimensions(other);
        if (!isCompressed())
        {
            compress();
        }
        final int otherNumColumns = other.getNumColumns();
        DenseMatrix result = new DenseMatrix(getNumRows(), otherNumColumns);
        int curRow = 0;
        for (int i = 0; i < values.length; ++i)
        {
            while (i >= firstIndicesForRows[curRow + 1])
            {
                ++curRow;
            }
            for (int j = 0; j < otherNumColumns; ++j)
            {
                result.row(curRow).values[j] += values[i]
                    * other.row(columnIndices[i]).values[j];
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * @return {@inheritDoc}
     */
    @Override
    public final Matrix times(
        final DiagonalMatrix other)
    {
        this.assertMultiplicationDimensions(other);
        if (!isCompressed())
        {
            compress();
        }
        SparseMatrix result = new SparseMatrix(getNumRows(), getNumColumns());
        result.columnIndices = Arrays.copyOf(columnIndices, columnIndices.length);
        result.firstIndicesForRows = Arrays.copyOf(firstIndicesForRows,
            firstIndicesForRows.length);
        result.values = new double[values.length];
        for (int i = 0; i < values.length; ++i)
        {
            result.values[i] = values[i] * other.get(columnIndices[i], columnIndices[i]);
        }

        return result;
    }

    /**
     * Package-private helper for the diagonal matrix class. This handles
     * diagonal*sparse calculation (so that sparse logic need not be embedded
     * unnecessarily into the diagonal class).
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * @param other The matrix to pre-multiply this by
     * @return The sparse matrix (compressed Yale format) resulting from
     * multiplying other * this.
     */
    public final Matrix preTimes(
        final DiagonalMatrix other)
    {
        other.assertMultiplicationDimensions(this);
        if (!isCompressed())
        {
            compress();
        }
        SparseMatrix result = new SparseMatrix(getNumRows(), getNumColumns());
        result.columnIndices = Arrays.copyOf(columnIndices, columnIndices.length);
        result.firstIndicesForRows = Arrays.copyOf(firstIndicesForRows,
            firstIndicesForRows.length);
        result.values = new double[values.length];
        int curRow = 0;
        for (int i = 0; i < values.length; ++i)
        {
            while (i >= firstIndicesForRows[curRow + 1])
            {
                ++curRow;
            }
            result.values[i] = values[i] * other.get(curRow, curRow);
        }

        return result;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * @return {@inheritDoc}
     */
    @Override
    // Not final because this method is overridden by the Parallel implementation
    public Vector times(
        final SparseVector vector)
    {
        vector.assertDimensionalityEquals(this.getNumColumns());
        if (!isCompressed())
        {
            compress();
        }
        if (!vector.isCompressed())
        {
            vector.compress();
        }

        DenseVector result = new DenseVector(numRows);

        int idx;
        int[] vLocs = vector.getIndices();
        double[] vVals = vector.getValues();
        for (int i = 0; i < numRows; ++i)
        {
            result.values[i] = 0;
            idx = 0;
            // For all non-zero elements on this row of the matrix
            for (int j = firstIndicesForRows[i]; j < firstIndicesForRows[i + 1]; ++j)
            {
                // First advance past non-zero elements in the vector that are
                // before this one
                while ((idx < vLocs.length) && (vLocs[idx] < columnIndices[j]))
                {
                    ++idx;
                }
                // If you've exceeded the length of the vector, you can stop
                // this row
                if (idx >= vLocs.length)
                {
                    break;
                }
                // If the vector's current location is past this point in the
                // row, then there's no non-zero element at this location
                if (vLocs[idx] > columnIndices[j])
                {
                    continue;
                }
                // You only reach here if they are at the same location
                result.values[i] += values[j] * vVals[idx];
            }
        }

        return new SparseVector(result);
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * 
     * @return {@inheritDoc}
     */
    @Override
    // Not final because this method is overridden by the Parallel implementation
    public Vector times(
        final DenseVector vector)
    {
        vector.assertDimensionalityEquals(this.getNumColumns());
        if (!isCompressed())
        {
            compress();
        }

        DenseVector result = new DenseVector(numRows);

        for (int i = 0; i < numRows; ++i)
        {
            result.values[i] = 0;
            for (int j = firstIndicesForRows[i]; j < firstIndicesForRows[i + 1]; ++j)
            {
                result.values[i] += values[j] * vector.values[columnIndices[j]];
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * @param scaleFactor {@inheritDoc}
     */
    @Override
    final public void scaleEquals(
        final double scaleFactor)
    {
        if (!isCompressed())
        {
            compress();
        }
        for (int i = 0; i < values.length; ++i)
        {
            values[i] *= scaleFactor;
        }
    }

    /**
     * {@inheritDoc}
     *
     * No change to compressed Yale or sparse row format.
     * @return {@inheritDoc}
     */
    @Override
    final public int getNumRows()
    {
        return numRows;
    }

    /**
     * {@inheritDoc}
     *
     * No change to compressed Yale or sparse row format.
     * @return {@inheritDoc}
     */
    @Override
    final public int getNumColumns()
    {
        return numCols;
    }

    /**
     * {@inheritDoc}
     *
     * No change to compressed Yale or sparse row format.
     * @param rowIndex {@inheritDoc}
     * @param columnIndex {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public double get(
        final int rowIndex,
        final int columnIndex)
    {
        if (!isCompressed())
        {
            return rows[rowIndex].get(columnIndex);
        }
        else
        {
            if (columnIndex < 0 || columnIndex >= numCols)
            {
                throw new ArrayIndexOutOfBoundsException(
                    "Unable to index column "
                    + columnIndex);
            }
            for (int k = firstIndicesForRows[rowIndex]; k
                < firstIndicesForRows[rowIndex
                + 1]; ++k)
            {
                if (columnIndices[k] == columnIndex)
                {
                    return values[k];
                }
                else if (columnIndices[k] > columnIndex)
                {
                    return 0;
                }
            }
            return 0;
        }
    }

    /**
     * {@inheritDoc}
     *
     * No change to compressed Yale or sparse row format.
     * @param rowIndex {@inheritDoc}
     * @param columnIndex {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    final public double getElement(
        final int rowIndex,
        final int columnIndex)
    {
        return this.get(rowIndex, columnIndex);
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * @param rowIndex {@inheritDoc}
     * @param columnIndex {@inheritDoc}
     * @param value {@inheritDoc}
     * @throws ArrayIndexOutOfBoundsException if the indices are out of bounds
     */
    @Override
    public void set(
        final int rowIndex,
        final int columnIndex,
        final double value)
    {
        setElement(rowIndex, columnIndex, value);
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * @param rowIndex {@inheritDoc}
     * @param columnIndex {@inheritDoc}
     * @param value {@inheritDoc}
     * @throws ArrayIndexOutOfBoundsException if the indices are out of bounds
     */
    @Override
    final public void setElement(
        final int rowIndex,
        final int columnIndex,
        final double value)
    {
        if (isCompressed())
        {
            decompress();
        }
        rows[rowIndex].setElement(columnIndex, value);
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion, this is in compressed Yale format. Return value is
     * also in compressed Yale format.
     *
     * @param minRow {@inheritDoc}
     * @param maxRow {@inheritDoc}
     * @param minColumn {@inheritDoc}
     * @param maxColumn {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ArrayIndexOutOfBoundsException if the input indices are outside
     * the acceptable bounds
     */
    @Override
    final public Matrix getSubMatrix(
        final int minRow,
        final int maxRow,
        final int minColumn,
        final int maxColumn)
    {
        checkSubmatrixRange(minRow, maxRow, minColumn, maxColumn);
        if (!isCompressed())
        {
            compress();
        }

        SparseMatrix result = new SparseMatrix(maxRow - minRow + 1, maxColumn
            - minColumn + 1, true);
        // First, count the number of elements in the new output
        int nnz = 0;
        for (int i = minRow; i <= maxRow; ++i)
        {
            for (int j = firstIndicesForRows[i]; j < firstIndicesForRows[i + 1]; ++j)
            {
                if (columnIndices[j] >= minColumn && columnIndices[j] <= maxColumn)
                {
                    ++nnz;
                }
            }
        }
        // Initialize the compressed Yale format in result
        result.values = new double[nnz];
        result.columnIndices = new int[nnz];
        result.firstIndicesForRows = new int[result.numRows + 1];
        // Load in the values from this
        int idx = 0;
        for (int i = minRow; i <= maxRow; ++i)
        {
            result.rows[i - minRow] = new SparseVector(result.numCols);
            result.firstIndicesForRows[i - minRow] = idx;
            for (int j = firstIndicesForRows[i]; j < firstIndicesForRows[i + 1]; ++j)
            {
                if (columnIndices[j] >= minColumn && columnIndices[j] <= maxColumn)
                {
                    result.values[idx] = values[j];
                    result.columnIndices[idx] = columnIndices[j] - minColumn;
                    ++idx;
                }
            }
        }
        result.firstIndicesForRows[maxRow - minRow + 1] = idx;

        return result;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * @param effectiveZero  {@inheritDoc}
     * @return {@inheritDoc}
     * @throws IllegalArgumentException if effectiveZero less than zero.
     */
    @Override
    final public boolean isSymmetric(
        final double effectiveZero)
    {
        ArgumentChecker.assertIsNonNegative("effectiveZero", effectiveZero);
        if (numRows != numCols)
        {
            return false;
        }

        if (!isCompressed())
        {
            compress();
        }

        int rowNum = 0;
        for (int i = 0; i < values.length; ++i)
        {
            while (i >= firstIndicesForRows[rowNum + 1])
            {
                ++rowNum;
            }
            if (Math.abs(values[i] - get(columnIndices[i], rowNum))
                > effectiveZero)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * 
     * @return {@inheritDoc}
     */
    @Override
    final public boolean isZero()
    {
        if (!isCompressed())
        {
            compress();
        }

        // This is a shortcut
        if (values.length == 0)
        {
            return true;
        }

        // There's a chance of zero values in vals after some operations
        return isZero(0);
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * @param effectiveZero  {@inheritDoc}
     * @return {@inheritDoc}
     * @throws IllegalArgumentException if effectiveZero less than zero.
     */
    @Override
    final public boolean isZero(
        final double effectiveZero)
    {
        ArgumentChecker.assertIsNonNegative("effectiveZero", effectiveZero);
        if (!isCompressed())
        {
            compress();
        }

        for (double d : values)
        {
            if (Math.abs(d) > effectiveZero)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion, this is in compressed Yale format. Returned sparse
     * matrix is in sparse vector format.
     * 
     * @return {@inheritDoc}
     */
    @Override
    final public Matrix transpose()
    {
        SparseMatrix result = new SparseMatrix(numCols, numRows);
        if (!isCompressed())
        {
            compress();
        }

        int rowNum = 0;
        for (int i = 0; i < values.length; ++i)
        {
            while (i >= firstIndicesForRows[rowNum + 1])
            {
                ++rowNum;
            }
            result.setElement(columnIndices[i], rowNum, values[i]);
        }

        return result;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * This is implemented by creating a new dense matrix version of this and
     * calling its inverse method -- inverting a sparse matrix is likely to
     * generate a dense matrix anyway. We would recommend using an iterative
     * solver (like Conjugate Gradient).
     * 
     * @return {@inheritDoc}
     */
    @Override
    final public Matrix inverse()
    {
        if (!isCompressed())
        {
            compress();
        }
        // NOTE: Inverting a sparse matrix generally creates a dense matrix
        // anyway.  Sparse matrices make the most sense for iterative solvers
        return (new DenseMatrix(this)).inverse();
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * This is implemented by creating a new dense matrix version of this and
     * calling its pseudoInverse method -- inverting a sparse matrix is likely
     * to generate a dense matrix anyway. We would recommend using an iterative
     * solver (like Conjugate Gradient).
     * 
     * @param effectiveZero  {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    final public Matrix pseudoInverse(
        final double effectiveZero)
    {
        if (!isCompressed())
        {
            compress();
        }
        // NOTE: Pseudo-inverting a sparse matrix generally creates a dense matrix
        // anyway.  Sparse matrices make the most sense for iterative solvers
        return (new DenseMatrix(this)).pseudoInverse(effectiveZero);
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * This is implemented by creating a new dense matrix version of this and
     * calling its logDeterminant method -- It requires factoring the matrix
     * which is going to be memory intense anyway.
     * 
     * @return {@inheritDoc}
     */
    @Override
    final public ComplexNumber logDeterminant()
    {
        if (!isCompressed())
        {
            compress();
        }
        // NOTE: I'm using LU decomposition to do this, so ... to Dense!
        return (new DenseMatrix(this)).logDeterminant();
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * This is implemented by creating a new dense matrix version of this and
     * calling its rank method -- It requires factoring the matrix which is
     * going to memory intense anyway.
     * 
     * @param effectiveZero  {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    final public int rank(
        final double effectiveZero)
    {
        if (!isCompressed())
        {
            compress();
        }
        // NOTE: This generally requires factoring the matrix which quickly
        // creates dense matrices anyway
        return (new DenseMatrix(this)).rank(effectiveZero);
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * 
     * @return {@inheritDoc}
     */
    @Override
    public double normFrobeniusSquared()
    {
        if (!isCompressed())
        {
            compress();
        }
        double result = 0;
        for (int i = 0; i < values.length; ++i)
        {
            result += values[i] * values[i];
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * 
     * @return {@inheritDoc}
     */
    @Override
    final public double normFrobenius()
    {
        return Math.sqrt(normFrobeniusSquared());
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: No change to the internal format of this.
     * 
     * @return {@inheritDoc}
     */
    @Override
    final public boolean isSquare()
    {
        return numRows == numCols;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * This is implemented by creating a new dense matrix version of this and
     * calling its solve method -- It requires factoring the matrix which is
     * going to memory intense anyway. We recommend an iterative solver instead.
     * 
     * @param B {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    final public Matrix solve(
        final Matrix B)
    {
        if (!isCompressed())
        {
            compress();
        }
        // NOTE: Iterative solvers are the way to go with sparse matrices.
        // Direct solution of a sparse matrix generally creates a dense matrix
        // anyway.  Sparse matrices make the most sense for iterative solvers
        return (new DenseMatrix(this)).solve(B);
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     *
     * This is implemented by creating a new dense matrix version of this and
     * calling its solve method -- It requires factoring the matrix which is
     * going to memory intense anyway. We recommend an iterative solver instead.
     * 
     * @param b {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    final public Vector solve(
        final Vector b)
    {
        if (!isCompressed())
        {
            compress();
        }
        // NOTE: Iterative solvers are the way to go with sparse matrices.
        // Direct solution of a sparse matrix generally creates a dense matrix
        // anyway.  Sparse matrices make the most sense for iterative solvers
        return (new DenseMatrix(this)).solve(b);
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     */
    @Override
    final public void identity()
    {
        // First get rid of any values already in there
        for (int i = 0; i < numRows; ++i)
        {
            rows[i].clear();
        }
        // Kill compressed representation if there ... update to new values
        int diagLen = (numRows <= numCols) ? numRows : numCols;
        values = new double[diagLen];
        Arrays.fill(values, 1);
        firstIndicesForRows = getZeroToNMinusOneArray(numRows + 1);
        columnIndices = getZeroToNMinusOneArray(diagLen);

        // We just need to update the first indices for the rows if this is a
        // tall rectangle matrix
        for (int i = diagLen; i < numRows + 1; ++i)
        {
            firstIndicesForRows[i] = diagLen;
        }
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * 
     * @param columnIndex {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    final public Vector getColumn(
        final int columnIndex)
    {
        if (!isCompressed())
        {
            compress();
        }
        SparseVector result = new SparseVector(numRows);
        for (int i = 0; i < numRows; ++i)
        {
            result.setElement(i, get(i, columnIndex));
        }

        return result;
    }

    @Override
    public Vector sumOfColumns()
    {
        if (!isCompressed())
        {
            compress();
        }
        DenseVector result = new DenseVector(numRows);
        for (int i = 0; i < numRows; ++i)
        {
            result.values[i] = 0;
            for (int j = firstIndicesForRows[i]; j < firstIndicesForRows[i + 1]; ++j)
            {
                result.values[i] += values[j];
            }
        }

        return result;
    }

    @Override
    public Vector sumOfRows()
    {
        if (!isCompressed())
        {
            compress();
        }
        DenseVector result = new DenseVector(numCols);
        for (int i = 0; i < numRows; ++i)
        {
            for (int j = firstIndicesForRows[i]; j < firstIndicesForRows[i + 1]; ++j)
            {
                result.values[columnIndices[j]] += values[j];
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Internal format is unchanged after this method.
     * 
     * @param rowIndex {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    final public Vector getRow(
        final int rowIndex)
    {
        if (!isCompressed())
        {
            return new SparseVector(rows[rowIndex]);
        }
        else
        {
            SparseVector result = new SparseVector(numCols);
            for (int i = firstIndicesForRows[rowIndex]; i
                < firstIndicesForRows[rowIndex + 1]; ++i)
            {
                result.setElement(columnIndices[i], values[i]);
            }
            return result;
        }
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon ocmpletion this is in the compressed Yale format.
     *
     * @param parameters {@inheritDoc}
     * @throws IllegalArgumentException if parameters does not have the same
     * number of elements as this's full size (including all of the zero
     * values).
     */
    @Override
    final public void convertFromVector(
        final Vector parameters)
    {
        parameters.assertDimensionalityEquals(numRows * numCols);

        // Count how many non-zero elements there will be at the end
        int nnz = 0;
        final int d = parameters.getDimensionality();
        for (int i = 0; i < d; ++i)
        {
            if (parameters.get(i) != 0)
            {
                ++nnz;
            }
        }
        // Initialize the compressed Yale format
        firstIndicesForRows = new int[numRows + 1];
        columnIndices = new int[nnz];
        values = new double[nnz];
        int idx = 0;
        // Fill the data in
        for (int i = 0; i < numRows; ++i)
        {
            rows[i].clear();
            firstIndicesForRows[i] = idx;
            for (int j = 0; j < numCols; ++j)
            {
                double val = parameters.get(i + j * numRows);
                if (val != 0)
                {
                    columnIndices[idx] = j;
                    values[idx] = val;
                    ++idx;
                }
            }
        }
        firstIndicesForRows[numRows] = idx;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: This does not affect compressed Yale/sparse row representation --
     * either is handled.
     * 
     * @return {@inheritDoc}
     */
    @Override
    final public Vector convertToVector()
    {
        SparseVector result = new SparseVector(numRows * numCols);
        if (isCompressed())
        {
            for (int i = 0; i < numRows; ++i)
            {
                for (int j = firstIndicesForRows[i]; j
                    < firstIndicesForRows[i + 1];
                    ++j)
                {
                    result.setElement(i + columnIndices[j] * numRows, values[j]);
                }
            }
        }
        else
        {
            for (int i = 0; i < numRows; ++i)
            {
                rows[i].compress();
                for (int j = 0; j < rows[i].getIndices().length; ++j)
                {
                    result.setElement(i + rows[i].getIndices()[j] * numRows,
                        rows[i].getValues()[j]);
                }
            }
        }
        return result;
    }

    /**
     * Private helper class that reads sparse entries from a compressed
     * SparseMatrix. Does not support the edit operations.
     */
    final private class SparseMatrixEntry
        implements MatrixEntry
    {

        /**
         * The row index for this value
         */
        private int rowIndex;

        /**
         * The index for the column and value in the compressed data
         */
        private int columnValueIndex;

        /**
         * Initializes this instance with the necessary values
         *
         * @param rowIndex The row index for this value
         * @param columnValueIndex The index for the column and value in the
         * compressed data
         */
        public SparseMatrixEntry(
            final int rowIndex,
            final int columnValueIndex)
        {
            this.rowIndex = rowIndex;
            this.columnValueIndex = columnValueIndex;
        }

        /**
         * Returns the row index
         *
         * @return the row index
         */
        @Override
        public int getRowIndex()
        {
            return rowIndex;
        }

        @Override
        public void setRowIndex(
            final int rowIndex)
        {
            throw new UnsupportedOperationException(
                "This implementation immutable.");
        }

        /**
         * Returns the value stored herein
         *
         * @return the value stored herein
         */
        @Override
        public double getValue()
        {
            return values[columnValueIndex];
        }

        @Override
        public void setValue(
            final double value)
        {
            values[columnValueIndex] = value;
        }

        /**
         * Returns the column index
         *
         * @return the column index
         */
        @Override
        public int getColumnIndex()
        {
            return columnIndices[columnValueIndex];
        }

        @Override
        public void setColumnIndex(
            final int columnIndex)
        {
            throw new UnsupportedOperationException(
                "This implementation immutable.");
        }

    }

    /**
     * This private helper class returns MatrixEntries for a compressed
     * SparseMatrix.
     */
    final private class NonZeroEntryIterator
        implements Iterator<MatrixEntry>
    {

        /**
         * The row index for the next value
         */
        private int rowIdx;

        /**
         * The column and value index for the next value
         */
        private int columnValueIndex;

        /**
         * Initializes a new iterator starting at the first value in the matrix
         */
        public NonZeroEntryIterator()
        {
            rowIdx = columnValueIndex = 0;
            // If there are no-entry rows, we need to skip past them
            while (columnValueIndex >= firstIndicesForRows[rowIdx + 1])
            {
                ++rowIdx;
                if (rowIdx >= numRows) {
                    break;
                }
            }
        }

        /**
         * Initializes a new iterator starting at the first value in the first
         * row of the matrix
         *
         * @param startRow The row to start on
         * @throws IllegalArgumentException if startRow is outside of [0 ..
         * numRows]
         */
        public NonZeroEntryIterator(
            final int startRow)
        {
            if (startRow >= getNumRows() || startRow < 0)
            {
                throw new IllegalArgumentException("Input row index ("
                    + startRow + ") greater than number of rows: "
                    + getNumRows());
            }
            rowIdx = startRow;
            columnValueIndex = firstIndicesForRows[rowIdx];
            // If there are no-entry rows, we need to skip past them
            while (columnValueIndex >= firstIndicesForRows[rowIdx + 1])
            {
                ++rowIdx;
                if (rowIdx >= numRows) {
                    break;
                }
            }
        }

        /**
         * Returns true if there are more values to return
         *
         * @return true if there are more values to return (else false)
         */
        @Override
        public boolean hasNext()
        {
            return columnValueIndex < values.length;
        }

        /**
         * Returns the next value in the matrix. Updates internal state past
         * this entry.
         *
         * @return the next value in the matrix
         */
        @Override
        public MatrixEntry next()
        {
            SparseMatrixEntry result
                = new SparseMatrixEntry(rowIdx, columnValueIndex);
            columnValueIndex++;
            // If there are no-entry rows, we need to skip past them
            while (columnValueIndex >= firstIndicesForRows[rowIdx + 1])
            {
                ++rowIdx;
                if (rowIdx >= numRows) {
                    break;
                }
            }

            return result;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException(
                "This implementation immutable.");
        }

    }

    @Override
    public Iterator<MatrixEntry> iterator()
    {
        if (!isCompressed())
        {
            compress();
        }
        return new NonZeroEntryIterator();
    }
        
    /**
     * Returns an iterator over the non-zero entries in this matrix. The
     * returned iterator is invalidated by any subsequent changes to the
     * referenced matrix. Any follow-on calls to the iterator after any changes
     * to the referred instance will provide undefined results (up to and
     * including throwing Exceptions).
     *
     * @return an iterator over the non-zero entries in this matrix
     * (left-to-right, top-to-bottom).
     */
    @Deprecated
    final public Iterator<MatrixEntry> getNonZeroValueIterator()
    {
        return iterator();
    }

    /**
     * Returns an iterator over the non-zero entries in this matrix starting
     * with startRow. The returned iterator is invalidated by any subsequent
     * changes to the referenced matrix. Any follow-on calls to the iterator
     * after any changes to the referred instance will provide undefined results
     * (up to and including throwing Exceptions).
     *
     * @param startRow The row to begin reading non-zero entries from
     * @return an iterator over the non-zero entries in this matrix
     * (left-to-right, top-to-bottom).
     */
    final public Iterator<MatrixEntry> getNonZeroValueIterator(
        final int startRow)
    {
        if (!isCompressed())
        {
            compress();
        }
        return new NonZeroEntryIterator(startRow);
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * 
     * @return {@inheritDoc}
     */
    @Override
    public final Vector preTimes(
        final SparseVector vector)
    {
        vector.assertDimensionalityEquals(this.getNumRows());
        if (!isCompressed())
        {
            compress();
        }
        SparseVector result = new SparseVector(getNumColumns());
        vector.compress();
        final int[] otherIndices = vector.getIndices();
        final double[] otherValues = vector.getValues();
        for (int j = 0; j < vector.getIndices().length; ++j)
        {
            for (int i = firstIndicesForRows[otherIndices[j]]; i
                < firstIndicesForRows[otherIndices[j] + 1]; ++i)
            {
                result.setElement(columnIndices[i], result.get(columnIndices[i])
                    + values[i] * otherValues[j]);
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     *
     * NOTE: Upon completion this is in the compressed Yale format.
     * 
     * @return {@inheritDoc}
     */
    @Override
    public final Vector preTimes(
        final DenseVector vector)
    {
        vector.assertDimensionalityEquals(this.getNumRows());
        if (!isCompressed())
        {
            compress();
        }
        DenseVector result = new DenseVector(getNumColumns());
        int row = 0;
        for (int i = 0; i < values.length; ++i)
        {
            while (i >= firstIndicesForRows[row + 1])
            {
                ++row;
            }
            result.setElement(columnIndices[i], result.get(columnIndices[i])
                + vector.get(row) * values[i]);
        }

        return result;
    }

    /**
     * Package-private helper that allows other matrices direct access to
     * setting the sparse vector rows of this. Be very careful when calling this
     * -- you have direct access to the data!
     *
     * NOTE: Upon completion, this is in the sparse vector format.
     *
     * @param i The row index
     * @param v The vector that to put in the new row
     */
    final void setRowInternal(
        final int i,
        final SparseVector v)
    {
        if (isCompressed())
        {
            decompress();
        }

        rows[i] = v;
    }

    @Override
    public MatrixFactory<?> getMatrixFactory()
    {
        return CustomSparseMatrixFactory.INSTANCE;
    }

    /**
     * Called by serialization (through the magic of Java reflection) and
     * shouldn't be called by anyone else.
     *
     * @param oos The stream to write this to
     * @throws IOException If there's a problem
     */
    private void writeObject(
        final ObjectOutputStream oos)
        throws IOException
    {
        compress();
        oos.writeInt(numRows);
        oos.writeInt(numCols);
        oos.writeObject(columnIndices);
        oos.writeObject(firstIndicesForRows);
        oos.writeObject(values);
    }

    /**
     * Called by de-serialization (through the magic of Java reflect) and
     * shouldn't be called by anyone else.
     *
     * @param ois The stream to read this from
     * @throws IOException If there's a problem
     * @throws ClassNotFoundException If there's a problem
     */
    private void readObject(
        final ObjectInputStream ois)
        throws IOException, ClassNotFoundException
    {
        numRows = ois.readInt();
        numCols = ois.readInt();
        columnIndices = (int[]) ois.readObject();
        firstIndicesForRows = (int[]) ois.readObject();
        values = (double[]) ois.readObject();
        rows = new SparseVector[numRows];
        for (int i = 0; i < this.numRows; ++i)
        {
            rows[i] = new SparseVector(numCols);
        }
    }

}
