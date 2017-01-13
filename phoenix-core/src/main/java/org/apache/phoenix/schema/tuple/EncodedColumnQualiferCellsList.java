/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.schema.tuple;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.phoenix.query.QueryConstants.ENCODED_CQ_COUNTER_INITIAL_VALUE;
import static org.apache.phoenix.query.QueryConstants.ENCODED_EMPTY_COLUMN_NAME;

import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;

import javax.annotation.concurrent.NotThreadSafe;

import org.apache.hadoop.hbase.Cell;
import org.apache.phoenix.schema.PTable.QualifierEncodingScheme;

/**
 * List implementation that provides indexed based look up when the cell column qualifiers are positive numbers. 
 * These qualifiers are generated by using one of the column qualifier encoding schemes specified in {@link QualifierEncodingScheme}. 
 * The api methods in this list assume that the caller wants to see
 * and add only non null elements in the list. 
 * <p>
 * Please note that this implementation doesn't implement all the optional methods of the 
 * {@link List} interface. Such unsupported methods could violate the basic invariance of the list that every cell with
 * an encoded column qualifier has a fixed position in the list.
 * </p>
 * <p>
 * An important performance characteristic of this list is that doing look up on the basis of index via {@link #get(int)}
 * is an O(n) operation. This makes iterating through the list using {@link #get(int)} an O(n^2) operation.
 * Instead, for iterating through the list, one should use the iterators created through {@link #iterator()} or 
 * {@link #listIterator()}. Do note that getting an element using {@link #getCellForColumnQualifier(int)} is an O(1) operation
 * and should generally be the way for accessing elements in the list.
 * </p> 
 */
@NotThreadSafe
public class EncodedColumnQualiferCellsList implements List<Cell> {

    private int minQualifier;
    private int maxQualifier;
    private int nonReservedRangeOffset;
    private final Cell[] array;
    private int numNonNullElements;
    private int firstNonNullElementIdx = -1;
    private static final int RESERVED_RANGE_SIZE = ENCODED_CQ_COUNTER_INITIAL_VALUE - ENCODED_EMPTY_COLUMN_NAME;
    // Used by iterators to figure out if the list was structurally modified.
    private int modCount = 0;
    private final QualifierEncodingScheme encodingScheme;

    public EncodedColumnQualiferCellsList(int minQ, int maxQ, QualifierEncodingScheme encodingScheme) {
        checkArgument(minQ <= maxQ, "Invalid arguments. Min: " + minQ
                + ". Max: " + maxQ);
        this.minQualifier = minQ;
        this.maxQualifier = maxQ;
        int size = 0;
        if (maxQ < ENCODED_CQ_COUNTER_INITIAL_VALUE) {
            size = RESERVED_RANGE_SIZE;
        } else if (minQ < ENCODED_CQ_COUNTER_INITIAL_VALUE) {
            size = (maxQ - minQ + 1);
        } else {
            size = RESERVED_RANGE_SIZE + (maxQ - minQ + 1);
        }
        this.array = new Cell[size];
        this.nonReservedRangeOffset = minQ > ENCODED_CQ_COUNTER_INITIAL_VALUE ? minQ  - ENCODED_CQ_COUNTER_INITIAL_VALUE : 0;
        this.encodingScheme = encodingScheme;
    }

    @Override
    public int size() {
        return numNonNullElements;
    }

    @Override
    public boolean isEmpty() {
        return numNonNullElements == 0;
    }

    @Override
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    @Override
    public Object[] toArray() {
        Object[] toReturn = new Object[numNonNullElements];
        int counter = 0;
        if (numNonNullElements > 0) {
            for (int i = 0; i < array.length; i++) {
                if (array[i] != null) {
                    toReturn[counter++] = array[i];
                }
            }
        }
        return toReturn;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        T[] toReturn =
                (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(),
                    numNonNullElements);
        int counter = 0;
        for (int i = 0; i < array.length; i++) {
            if (array[i] != null) {
                toReturn[counter++] = (T) array[i];
            }
        }
        return toReturn;
    }

    @Override
    public boolean add(Cell e) {
        if (e == null) {
            throw new NullPointerException();
        }
        int columnQualifier = encodingScheme.decode(e.getQualifierArray(), e.getQualifierOffset(), e.getQualifierLength());
                
        checkQualifierRange(columnQualifier);
        int idx = getArrayIndex(columnQualifier);
        if (array[idx] == null) {
            numNonNullElements++;
        }
        array[idx] = e;
        if (firstNonNullElementIdx == -1) {
            firstNonNullElementIdx = idx;
        } else if (idx < firstNonNullElementIdx) {
            firstNonNullElementIdx = idx;
        }
        modCount++;
        /*
         * Note that we don't care about equality of the element being added with the element
         * already present at the index.
         */
        return true;
    }

    @Override
    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        Cell e = (Cell) o;
        int i = 0;
        while (i < array.length) {
            if (array[i] != null && array[i].equals(e)) {
                array[i] = null;
                numNonNullElements--;
                if (numNonNullElements == 0) {
                    firstNonNullElementIdx = -1;
                } else if (firstNonNullElementIdx == i) {
                    // the element being removed was the first non-null element we knew
                    while (i < array.length && (array[i]) == null) {
                        i++;
                    }
                    if (i < array.length) {
                        firstNonNullElementIdx = i;
                    } else {
                        firstNonNullElementIdx = -1;
                    }
                }
                modCount++;
                return true;
            }
            i++;
        }
        return false;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        boolean containsAll = true;
        Iterator<?> itr = c.iterator();
        while (itr.hasNext()) {
            containsAll &= (indexOf(itr.next()) >= 0);
        }
        return containsAll;
    }

    @Override
    public boolean addAll(Collection<? extends Cell> c) {
        boolean changed = false;
        for (Cell cell : c) {
            if (c == null) {
                throw new NullPointerException();
            }
            changed |= add(cell);
        }
        return changed;
    }

    @Override
    public boolean addAll(int index, Collection<? extends Cell> c) {
        throwGenericUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        Iterator<?> itr = c.iterator();
        boolean changed = false;
        while (itr.hasNext()) {
            changed |= remove(itr.next());
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        boolean changed = false;
        // Optimize if the passed collection is an instance of EncodedColumnQualiferCellsList
        if (collection instanceof EncodedColumnQualiferCellsList) {
            EncodedColumnQualiferCellsList list = (EncodedColumnQualiferCellsList) collection;
            ListIterator<Cell> listItr = this.listIterator();
            while (listItr.hasNext()) {
                Cell cellInThis = listItr.next();
                int qualifier = encodingScheme.decode(cellInThis.getQualifierArray(),
                            cellInThis.getQualifierOffset(), cellInThis.getQualifierLength());
                try {
                    Cell cellInParam = list.getCellForColumnQualifier(qualifier);
                    if (cellInParam != null && cellInParam.equals(cellInThis)) {
                        continue;
                    }
                    listItr.remove();
                    changed = true;
                } catch (IndexOutOfBoundsException expected) {
                    // this could happen when the qualifier of cellInParam lies out of
                    // the range of this list.
                    listItr.remove();
                    changed = true;
                }
            }
        } else {
            throw new UnsupportedOperationException(
                    "Operation only supported for collections of type EncodedColumnQualiferCellsList");
        }
        return changed;
    }

    @Override
    public void clear() {
        for (int i = 0; i < array.length; i++) {
            array[i] = null;
        }
        firstNonNullElementIdx = -1;
        numNonNullElements = 0;
        modCount++;
    }

    @Override
    public Cell get(int index) {
        rangeCheck(index);
        int numNonNullElementsFound = 0;
        for (int i = firstNonNullElementIdx; i < array.length; i++) {
            if (array[i] != null) {
                numNonNullElementsFound++;
                if (numNonNullElementsFound == index + 1) {
                    return array[i];
                }
            }
        }
        throw new IllegalStateException("There was no element present in the list at index "
                + index + " even though number of elements in the list are " + size());
    }

    @Override
    public Cell set(int index, Cell e) {
        throwGenericUnsupportedOperationException();
        return null;
    }

    @Override
    public void add(int index, Cell element) {
        throwGenericUnsupportedOperationException();
    }

    @Override
    public Cell remove(int index) {
        throwGenericUnsupportedOperationException();
        return null;
    }

    @Override
    public int indexOf(Object o) {
        if (o == null || isEmpty()) {
            return -1;
        } else {
            int numNonNull = -1;
            for (int i = 0; i < array.length; i++) {
                if (array[i] != null) {
                    numNonNull++;
                }
                if (o.equals(array[i])) {
                    return numNonNull;
                }
            }
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        if (o == null || isEmpty()) {
            return -1;
        }
        int lastIndex = numNonNullElements;
        for (int i = array.length - 1; i >= 0; i--) {
            if (array[i] != null) {
                lastIndex--;
            }
            if (o.equals(array[i])) {
                return lastIndex;
            }
        }
        return -1;
    }

    @Override
    public ListIterator<Cell> listIterator() {
        return new ListItr();
    }

    @Override
    public ListIterator<Cell> listIterator(int index) {
        throwGenericUnsupportedOperationException();
        return null;
    }

    @Override
    public List<Cell> subList(int fromIndex, int toIndex) {
        throwGenericUnsupportedOperationException();
        return null;
    }

    @Override
    public Iterator<Cell> iterator() {
        return new Itr();
    }

    public Cell getCellForColumnQualifier(byte[] qualifierBytes) {
        int columnQualifier = encodingScheme.decode(qualifierBytes);
        return getCellForColumnQualifier(columnQualifier);
    }
    
    private Cell getCellForColumnQualifier(int columnQualifier) {
        checkQualifierRange(columnQualifier);
        int idx = getArrayIndex(columnQualifier);
        Cell c = array[idx];
        return c;
    }

    public Cell getFirstCell() {
        if (firstNonNullElementIdx == -1) {
            throw new NoSuchElementException("No elements present in the list");
        }
        return array[firstNonNullElementIdx];
    }

    private void checkQualifierRange(int qualifier) {
        if (qualifier < ENCODED_CQ_COUNTER_INITIAL_VALUE) {
            return; // space in the array for reserved range is always allocated. 
        }
        if (qualifier < minQualifier || qualifier > maxQualifier) {
            throw new IndexOutOfBoundsException("Qualifier " + qualifier
                    + " is out of the valid range - (" + minQualifier + ", " + maxQualifier + ")");
        }
    }

    private void rangeCheck(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException();
        }
    }

    private int getArrayIndex(int columnQualifier) {
        checkArgument(columnQualifier >= ENCODED_EMPTY_COLUMN_NAME);
        if (columnQualifier < ENCODED_CQ_COUNTER_INITIAL_VALUE) {
            return columnQualifier;
        }
        return columnQualifier - nonReservedRangeOffset;
    }

    private void throwGenericUnsupportedOperationException() {
        throw new UnsupportedOperationException(
                "Operation cannot be supported because it potentially violates the invariance contract of this list implementation");
    }

    private class Itr implements Iterator<Cell> {
        protected int nextIndex = 0;
        protected int lastRet = -1;
        protected int expectedModCount = modCount;
        
        private Itr() {
            moveForward(true);
        }

        @Override
        public boolean hasNext() {
            return nextIndex != -1;
        }

        @Override
        public Cell next() {
            checkForCoModification();
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Cell next = array[nextIndex];
            lastRet = nextIndex;
            moveForward(false);
            modCount++;
            expectedModCount = modCount;
            return next;
        }

        @Override
        public void remove() {
            if (lastRet < 0) {
                throw new IllegalStateException();
            }
            checkForCoModification();
            array[lastRet] = null;
            lastRet = -1;
            numNonNullElements--;
            modCount++;
            expectedModCount = modCount;
        }

        protected void moveForward(boolean init) {
            int i = init ? 0 : nextIndex + 1;
            while (i < array.length && (array[i]) == null) {
                i++;
            }
            if (i < array.length) {
                nextIndex = i;
            } else {
                nextIndex = -1;
            }
        }
        
        protected void checkForCoModification() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
        }

    }

    private class ListItr extends Itr implements ListIterator<Cell> {
        private int previousIndex = -1;
        
        private ListItr() {
            moveForward(true);
        }

        @Override
        public boolean hasNext() {
            return nextIndex != -1;
        }

        @Override
        public boolean hasPrevious() {
            return previousIndex != -1;
        }

        @Override
        public Cell previous() {
            if (previousIndex == -1) {
                throw new NoSuchElementException();
            }
            checkForCoModification();
            lastRet = previousIndex;
            movePointersBackward();
            return array[lastRet];
        }

        @Override
        public int nextIndex() {
            return nextIndex;
        }

        @Override
        public int previousIndex() {
            return previousIndex;
        }

        @Override
        public void remove() {
            if (lastRet == nextIndex) {
                moveNextPointer(nextIndex);
            }
            super.remove();
            expectedModCount = modCount;
        }

        @Override
        public void set(Cell e) {
            if (lastRet == -1) {
                throw new IllegalStateException();
            }
            int columnQualifier = encodingScheme.decode(e.getQualifierArray(), e.getQualifierOffset(), e.getQualifierLength());                    
            int idx = getArrayIndex(columnQualifier);
            if (idx != lastRet) {
                throw new IllegalArgumentException("Cell " + e + " with column qualifier "
                        + columnQualifier + " belongs at index " + idx
                        + ". It cannot be added at the position " + lastRet
                        + " to which the previous next() or previous() was pointing to.");
            }
            EncodedColumnQualiferCellsList.this.add(e);
            expectedModCount = modCount;
        }

        @Override
        public void add(Cell e) {
            throwGenericUnsupportedOperationException();
        }
        
        @Override
        protected void moveForward(boolean init) {
            if (!init) {
                previousIndex = nextIndex;
            }
            int i = init ? 0 : nextIndex + 1; 
            moveNextPointer(i);
        }

        private void moveNextPointer(int i) {
            while (i < array.length && (array[i]) == null) {
                i++;
            }
            if (i < array.length) {
                nextIndex = i;
            } else {
                nextIndex = -1;
            }
        }

        private void movePointersBackward() {
            nextIndex = previousIndex;
            int i = previousIndex - 1;
            movePreviousPointer(i);
        }

        private void movePreviousPointer(int i) {
            for (; i >= 0; i--) {
                if (array[i] != null) {
                    previousIndex = i;
                    break;
                }
            }
            if (i < 0) {
                previousIndex = -1;
            }
        }
    }

}
