/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang;

import jdk.internal.vm.annotation.Stable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MutableCallSite;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lazily associate a computed value with (potentially) every type.
 * For example, if a dynamic language needs to construct a message dispatch
 * table for each class encountered at a message send call site,
 * it can use a {@code ClassValue} to cache information needed to
 * perform the message send quickly, for each class encountered.
 * @author John Rose, JSR 292 EG
 * @since 1.7
 */
public abstract class ClassValue<T> {
    private static final int MIN_CVS = 1 << 4;
    private static final int MAX_CVS = 1 << 30;
    private static final AtomicLong idGen = new AtomicLong();
    private static final AtomicInteger indexGen = new AtomicInteger();

    @Stable
    private final long id = idGen.incrementAndGet();
    @Stable
    private final int index;

    /**
     * Sole constructor.  (For invocation by subclass constructors, typically
     * implicit.)
     */
    protected ClassValue() {
        int i = FreeIndex.poll();
        if (i == 0) {
            i = indexGen.incrementAndGet();
            if (i > MAX_CVS || i < 0) {
                indexGen.decrementAndGet();
                System.gc();
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                i = FreeIndex.poll();
                if (i == 0) {
                    throw new OutOfMemoryError("Number of live ClazzValue instances exceeded " + MAX_CVS);
                }
            }
        }
        this.index = i;
        new FreeIndex(this);
    }

    /**
     * Computes the given class's derived value for this {@code ClassValue}.
     * <p>
     * This method will be invoked within the first thread that accesses
     * the value with the {@link #get get} method.
     * <p>
     * Normally, this method is invoked at most once per class,
     * but it may be invoked again if there has been a call to
     * {@link #remove remove}.
     * <p>
     * If this method throws an exception, the corresponding call to {@code get}
     * will terminate abnormally with that exception, and no class value will be recorded.
     *
     * @param type the type whose class value must be computed
     * @return the newly computed value associated with this {@code ClassValue}, for the given class or interface
     * @see #get
     * @see #remove
     */
    protected abstract T computeValue(Class<?> type);

    /**
     * Returns the value for the given class.
     * If no value has yet been computed, it is obtained by
     * an invocation of the {@link #computeValue computeValue} method.
     * <p>
     * The actual installation of the value on the class
     * is performed atomically.
     * At that point, if several racing threads have
     * computed values, one is chosen, and returned to
     * all the racing threads.
     * <p>
     * The {@code type} parameter is typically a class, but it may be any type,
     * such as an interface, a primitive type (like {@code int.class}), or {@code void.class}.
     * <p>
     * In the absence of {@code remove} calls, a class value has a simple
     * state diagram:  uninitialized and initialized.
     * When {@code remove} calls are made,
     * the rules for value observation are more complex.
     * See the documentation for {@link #remove remove} for more information.
     *
     * @param type the type whose class value must be computed or retrieved
     * @return the current value associated with this {@code ClassValue}, for the given class or interface
     * @throws NullPointerException if the argument is null
     * @see #remove
     * @see #computeValue
     */
    public T get(Class<?> type) {
        McsEntry[] cvTable = cvTable(type, index, true);
        McsEntry entry = cvTable[index - 1];
        if (entry != null) {
            ValueId<T> valueId = getValueId(entry);
            if (valueId.cvId == id) {
                return valueId.value;
            }
        }
        return getSlow(type);
    }

    /**
     * Removes the associated value for the given class.
     * If this value is subsequently {@linkplain #get read} for the same class,
     * its value will be reinitialized by invoking its {@link #computeValue computeValue} method.
     * This may result in an additional invocation of the
     * {@code computeValue} method for the given class.
     * <p>
     * In order to explain the interaction between {@code get} and {@code remove} calls,
     * we must model the state transitions of a class value to take into account
     * the alternation between uninitialized and initialized states.
     * To do this, number these states sequentially from zero, and note that
     * uninitialized (or removed) states are numbered with even numbers,
     * while initialized (or re-initialized) states have odd numbers.
     * <p>
     * When a thread {@code T} removes a class value in state {@code 2N},
     * nothing happens, since the class value is already uninitialized.
     * Otherwise, the state is advanced atomically to {@code 2N+1}.
     * <p>
     * When a thread {@code T} queries a class value in state {@code 2N},
     * the thread first attempts to initialize the class value to state {@code 2N+1}
     * by invoking {@code computeValue} and installing the resulting value.
     * <p>
     * When {@code T} attempts to install the newly computed value,
     * if the state is still at {@code 2N}, the class value will be initialized
     * with the computed value, advancing it to state {@code 2N+1}.
     * <p>
     * Otherwise, whether the new state is even or odd,
     * {@code T} will discard the newly computed value
     * and retry the {@code get} operation.
     * <p>
     * Discarding and retrying is an important proviso,
     * since otherwise {@code T} could potentially install
     * a disastrously stale value.  For example:
     * <ul>
     * <li>{@code T} calls {@code CV.get(C)} and sees state {@code 2N}
     * <li>{@code T} quickly computes a time-dependent value {@code V0} and gets ready to install it
     * <li>{@code T} is hit by an unlucky paging or scheduling event, and goes to sleep for a long time
     * <li>...meanwhile, {@code T2} also calls {@code CV.get(C)} and sees state {@code 2N}
     * <li>{@code T2} quickly computes a similar time-dependent value {@code V1} and installs it on {@code CV.get(C)}
     * <li>{@code T2} (or a third thread) then calls {@code CV.remove(C)}, undoing {@code T2}'s work
     * <li> the previous actions of {@code T2} are repeated several times
     * <li> also, the relevant computed values change over time: {@code V1}, {@code V2}, ...
     * <li>...meanwhile, {@code T} wakes up and attempts to install {@code V0}; <em>this must fail</em>
     * </ul>
     * We can assume in the above scenario that {@code CV.computeValue} uses locks to properly
     * observe the time-dependent states as it computes {@code V1}, etc.
     * This does not remove the threat of a stale value, since there is a window of time
     * between the return of {@code computeValue} in {@code T} and the installation
     * of the new value.  No user synchronization is possible during this time.
     *
     * @param type the type whose class value must be removed
     * @throws NullPointerException if the argument is null
     */
    public void remove(Class<?> type) {
        synchronized (cvTableLock(type)) {
            McsEntry[] cvTable = cvTable(type, index, false);
            if (cvTable != null) {
                McsEntry entry = cvTable[index - 1];
                if (entry != null) {
                    ValueId<?> valueId = getValueId(entry);
                    if (valueId.cvId != 0) {
                        entry.setTarget(ValueId.NONE_MH);
                    }
                }
            }
        }
    }

    // Possible functionality for JSR 292 MR 1
    /*public*/ void put(Class<?> type, T value) {
        synchronized (cvTableLock(type)) {
            McsEntry[] cvTable = cvTable(type, index, true);
            MethodHandle mh = MethodHandles.constant(ValueId.class, new ValueId<>(value, id));
            McsEntry entry = cvTable[index - 1];
            if (entry == null) {
                cvTable[index - 1] = new McsEntry(mh);
            } else {
                entry.setTarget(mh);
            }
        }
    }

    /// --------
    /// Implementation...
    /// --------

    private T getSlow(Class<?> type) {
        T value = computeValue(type);
        synchronized (cvTableLock(type)) {
            McsEntry[] cvTable = cvTable(type, index, true);
            McsEntry entry = cvTable[index - 1];
            if (entry == null) {
                MethodHandle mh = MethodHandles.constant(ValueId.class, new ValueId<>(value, id));
                entry = new McsEntry(mh);
                cvTable[index - 1] = entry;
                return value;
            }
            ValueId<T> valueId = getValueId(entry);
            if (valueId.cvId == id) {
                return valueId.value;
            }
            // entry != null but it is of a freed ClazzValue instance -> replace target
            MethodHandle mh = MethodHandles.constant(ValueId.class, new ValueId<>(value, id));
            entry.setTarget(mh);
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ValueId<T> getValueId(McsEntry entry) {
        try {
            return (ValueId<T>) entry.dynamicInvoker.invokeExact();
        } catch (Throwable e) {
            throw new InternalError(e);
        }
    }

    private static McsEntry[] cvTable(Class<?> type, int index, boolean createIfAbsent) {
        McsEntry[] cvTable = type.cvTable;
        if ((cvTable == null || cvTable.length < index) && createIfAbsent) {
            synchronized (cvTableLock(type)) {
                cvTable = type.cvTable;
                if (cvTable == null || cvTable.length < index) {
                    int newLen = Math.max(
                        MIN_CVS,
                        Integer.highestOneBit(index - 1) << 1
                    );
                    cvTable = cvTable == null
                              ? new McsEntry[newLen]
                              : Arrays.copyOf(cvTable, newLen);
                    type.cvTable = cvTable;
                }
            }
        }
        return cvTable == null || cvTable.length < index ? null : cvTable;
    }

    private static final Object CV_TABLE_LOCK_INIT_LOCK = new Object();

    private static Object cvTableLock(Class<?> type) {
        Object cvTableLock = type.cvTableLock;
        if (cvTableLock == null) {
            synchronized (CV_TABLE_LOCK_INIT_LOCK) {
                cvTableLock = type.cvTableLock;
                if (cvTableLock == null) {
                    type.cvTableLock = cvTableLock = new Object();
                }
            }
        }
        return cvTableLock;
    }

    /**
     * A tuple of:
     * <ul>
     *     <li>value - the value</li>
     *     <li>cvId - the ClassValue.id of the corresponding ClassValue</li>
     * </ul>
     *
     * @param <T> type of value
     */
    private static final class ValueId<T> {
        // an instance with null value and zero cvId (not equal to any real instance)
        private static final ValueId<?> NONE = new ValueId<>(null, 0L);
        private static final MethodHandle NONE_MH = MethodHandles.constant(ValueId.class, NONE);

        @Stable
        private final T value;
        @Stable
        private final long cvId;

        private ValueId(T value, long cvId) {
            this.value = value;
            this.cvId = cvId;
        }
    }

    /**
     * An entry holding a {@link MutableCallSite} and its {@code dynamicInvoker} which
     * is used to retrieve current constant value but which may get redefined by setting new
     * target.
     */
    static final class McsEntry {
        @Stable
        private final MutableCallSite mcs;
        @Stable
        private final MethodHandle dynamicInvoker;

        private McsEntry(MethodHandle target) {
            this.mcs = new MutableCallSite(target);
            this.dynamicInvoker = mcs.dynamicInvoker();
        }

        private void setTarget(MethodHandle target) {
            mcs.setTarget(target);
            MutableCallSite.syncAll(new MutableCallSite[]{mcs});
        }
    }

    /**
     * Tracks reachability of {@link ClassValue} being enqueued with CV's index when GCed.
     */
    private static final class FreeIndex extends PhantomReference<ClassValue<?>> {
        private static final ReferenceQueue<ClassValue<?>> queue = new ReferenceQueue<>();
        // special head instance that is never enqueued
        private static final FreeIndex head = new FreeIndex(null);

        private final int index;
        private FreeIndex prev, next;

        private FreeIndex(ClassValue<?> referent) {
            super(referent, referent == null ? null : queue);
            this.index = referent == null ? 0 : referent.index;
            if (referent == null) {
                prev = next = this;
            } else {
                synchronized (head) {
                    prev = head;
                    next = head.next;
                    head.next.prev = this;
                    head.next = this;
                }
            }
        }

        private static int poll() {
            FreeIndex fi = (FreeIndex) queue.poll();
            if (fi == null) {
                return 0;
            } else {
                synchronized (head) {
                    fi.prev.next = fi.next;
                    fi.next.prev = fi.prev;
                    fi.next = fi.prev = fi;
                }
                return fi.index;
            }
        }
    }
}
