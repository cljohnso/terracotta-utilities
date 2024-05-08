package org.terracotta.utilities.concurrent;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Provides a {@code Supplier} wrapper which calls the wrapped {@code Supplier.get}
 * method no more than once.
 */
public class OneTimeSupplier<T> implements Supplier<T> {
  private final Supplier<T> supplier;
  private volatile boolean cleared = false;
  private volatile T value;

  /**
   * Creates a {@code OneTimeSupplier} wrapping the supplied {@code Supplier}.
   * @param supplier the {@code Supplier} instance to wrap
   */
  public OneTimeSupplier(Supplier<T> supplier) {
    this.supplier = Objects.requireNonNull(supplier);
  }

  /**
   * {@inheritDoc}
   * <p>
   * This implementation returns calls the {@code get} method of the wrapped
   * {@code Supplier} once.  Each call to this method returns the same
   * object.
   * @return {@inheritDoc}
   * @throws IllegalStateException if this method is called after {@link #clear()}
   */
  @Override
  public T get() {
    T localValue = value;
    if (localValue == null) {
      synchronized (this) {
        if (cleared) {
          throw new IllegalStateException("cleared");
        }
        localValue = value;
        if (localValue == null) {
          localValue = value = supplier.get();
        }
      }
    }
    return localValue;
  }

  /**
   * Clears the object reference obtained from the wrapped {@code Supplier}.
   * Calling this method invalidates this {@code OneTimeSupplier} instance.
   */
  public synchronized void clear() {
    value = null;
    cleared = true;
  }
}
