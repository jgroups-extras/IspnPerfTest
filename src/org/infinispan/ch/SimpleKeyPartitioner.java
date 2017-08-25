package org.infinispan.ch;

import java.util.Arrays;

import org.infinispan.configuration.cache.HashConfiguration;
import org.infinispan.distribution.ch.KeyPartitioner;

/**
 * A {@link KeyPartitioner} implementation that uses the {@link Object#hashCode()} to set the segments.
 *
 * @author Pedro Ruivo
 * @since 1.0
 */
public class SimpleKeyPartitioner implements KeyPartitioner {

   private volatile int numSegments;

   @Override
   public void init(HashConfiguration configuration) {
      this.numSegments = configuration.numSegments();
   }

   @Override
   public int getSegment(Object o) {
      return (hash(o) & Integer.MAX_VALUE) % numSegments;
   }

   @Override
   public String toString() {
      return "SimpleKeyPartitioner{" +
            "numSegments=" + numSegments +
            '}';
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      }
      if (o == null || getClass() != o.getClass()) {
         return false;
      }

      SimpleKeyPartitioner that = (SimpleKeyPartitioner) o;

      return numSegments == that.numSegments;
   }

   @Override
   public int hashCode() {
      return numSegments;
   }

   private int hash(Object o) {
      return o.getClass().isArray() ?
            Arrays.hashCode((Object[]) o) :
            o.hashCode();
   }
}
