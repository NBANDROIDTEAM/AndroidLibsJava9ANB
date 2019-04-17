package libcore.util;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;
import dalvik.system.VMRuntime;
import sun.misc.Cleaner;

public class NativeAllocationRegistry {

    private final ClassLoader classLoader;
    private final long freeFunction;
    private final long size;

    public NativeAllocationRegistry(ClassLoader classLoader, long freeFunction, long size) {
        if (size < 0L) {
            throw new IllegalArgumentException("Invalid native allocation size: " + size);
        }
        this.classLoader = classLoader;
        this.freeFunction = freeFunction;
        this.size = size;
    }

    public Runnable registerNativeAllocation_Original(Object referent, long nativePtr) {
        CleanerRunner result;
        CleanerThunk thunk;
        if (referent == null) {
            throw new IllegalArgumentException("referent is null");
        }
        if (nativePtr == 0L) {
            throw new IllegalArgumentException("nativePtr is null");
        }
        try {
            thunk = new CleanerThunk(this);
            Cleaner cleaner = Cleaner.create(referent, thunk);
            result = new CleanerRunner(cleaner);
            NativeAllocationRegistry.registerNativeAllocation(this.size);
        } catch (VirtualMachineError vme) {
            NativeAllocationRegistry.applyFreeFunction(this.freeFunction, nativePtr);
            throw vme;
        }
        thunk.setNativePtr(nativePtr);
        return result;
    }

    @LayoutlibDelegate
    public Runnable registerNativeAllocation(Object object, long l) {
        return NativeAllocationRegistry_Delegate.registerNativeAllocation(this, object, l);
    }

    public Runnable registerNativeAllocation_Original(Object referent, Allocator allocator) {
        if (referent == null) {
            throw new IllegalArgumentException("referent is null");
        }
        CleanerThunk thunk = new CleanerThunk(this);
        Cleaner cleaner = Cleaner.create(referent, thunk);
        CleanerRunner result = new CleanerRunner(cleaner);
        long nativePtr = allocator.allocate();
        if (nativePtr == 0L) {
            cleaner.clean();
            return null;
        }
        NativeAllocationRegistry.registerNativeAllocation(this.size);
        thunk.setNativePtr(nativePtr);
        return result;
    }

    @LayoutlibDelegate
    public Runnable registerNativeAllocation(Object object, Allocator allocator) {
        return NativeAllocationRegistry_Delegate.registerNativeAllocation(this, object, allocator);
    }

    static void registerNativeAllocation_Original(long size) {
        VMRuntime.getRuntime().registerNativeAllocation((int) Math.min(size, Integer.MAX_VALUE));
    }

    @LayoutlibDelegate
    private static void registerNativeAllocation(long l) {
        NativeAllocationRegistry_Delegate.registerNativeAllocation(l);
    }

    private static void registerNativeFree(long size) {
        VMRuntime.getRuntime().registerNativeFree((int) Math.min(size, Integer.MAX_VALUE));
    }

    @LayoutlibDelegate
    public static void applyFreeFunction(long l, long l2) {
        NativeAllocationRegistry_Delegate.applyFreeFunction(l, l2);
    }

    private static class CleanerRunner
            implements Runnable {

        private final Cleaner cleaner;

        public CleanerRunner(Cleaner cleaner) {
            this.cleaner = cleaner;
        }

        @Override
        public void run() {
            this.cleaner.clean();
        }
    }

    private class CleanerThunk
            implements Runnable {

        private long nativePtr = 0L;
        final /* synthetic */ NativeAllocationRegistry this$0;

        public CleanerThunk(NativeAllocationRegistry nativeAllocationRegistry) {
            this.this$0 = nativeAllocationRegistry;
        }

        @Override
        public void run() {
            if (this.nativePtr == 0L) {
                return;
            }
            NativeAllocationRegistry.applyFreeFunction(this.this$0.freeFunction, this.nativePtr);
            NativeAllocationRegistry.registerNativeFree(this.this$0.size);
        }

        public void setNativePtr(long nativePtr) {
            this.nativePtr = nativePtr;
        }
    }

    public static interface Allocator {

        public long allocate();
    }

}
