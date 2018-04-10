package org.infinispan.ch;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.infinispan.commons.hash.Hash;
import org.infinispan.commons.marshall.Externalizer;
import org.infinispan.commons.marshall.SerializeWith;
import org.infinispan.distribution.ch.ConsistentHashFactory;
import org.infinispan.distribution.ch.impl.DefaultConsistentHash;
import org.infinispan.remoting.transport.Address;

/**
 * A {@link ConsistentHashFactory} that for each primary owner, the backup owners are the next in the view.
 *
 * @author Pedro Ruivo
 * @since 1.0
 */
@SerializeWith(SimpleConsistentHashFactory.SCHFExternalizer.class)
public class SimpleConsistentHashFactory implements ConsistentHashFactory<DefaultConsistentHash> {

   @Override
   public DefaultConsistentHash create(Hash hashFunction, int numOwners, int numSegments, List<Address> members,
         Map<Address, Float> capacityFactors) {
      List<Address>[] segmentOwners = new List[numSegments];
      for (int i = 0; i < numSegments; i++) {
         segmentOwners[i] = createOwnersCollection(members, numOwners, i);
      }
      return new DefaultConsistentHash(hashFunction, numOwners, numSegments, members, null, segmentOwners);
   }

   @Override
   public DefaultConsistentHash updateMembers(DefaultConsistentHash baseCH, List<Address> newMembers,
         Map<Address, Float> capacityFactors) {
      final int numOwners = baseCH.getNumOwners();
      final int numSegments = baseCH.getNumSegments();
      List<Address>[] segmentOwners = new List[numSegments];
      for (int i = 0; i < numSegments; i++) {
         List<Address> owners = new ArrayList<>(baseCH.locateOwnersForSegment(i));
         owners.retainAll(newMembers);
         if (owners.isEmpty()) {
            // updateMembers should only add new owners if there are no owners left
            owners = createOwnersCollection(newMembers, numOwners, i);
         }
         segmentOwners[i] = owners;
      }

      DefaultConsistentHash updated = new DefaultConsistentHash(baseCH.getHashFunction(), numOwners, numSegments,
            newMembers, null, segmentOwners);
      return baseCH.equals(updated) ? baseCH : updated;
   }

   @Override
   public DefaultConsistentHash rebalance(DefaultConsistentHash baseCH) {
      DefaultConsistentHash rebalanced = create(baseCH.getHashFunction(), baseCH.getNumOwners(),
            baseCH.getNumSegments(),
            baseCH.getMembers(), baseCH.getCapacityFactors());
      return baseCH.equals(rebalanced) ? baseCH : rebalanced;
   }

   @Override
   public DefaultConsistentHash union(DefaultConsistentHash ch1, DefaultConsistentHash ch2) {
      return ch1.union(ch2);
   }

   private List<Address> createOwnersCollection(List<Address> members, int numOwners, int segmentId) {
      List<Address> owners = new ArrayList<>(numOwners);
      int membersSize = members.size();
      for (int i = 0, j = segmentId; i < numOwners; ++i, j++) {
         owners.add(members.get(j % membersSize));
      }
      return owners;
   }

   public static class SCHFExternalizer implements Externalizer<SimpleConsistentHashFactory> {

      @Override
      public void writeObject(ObjectOutput objectOutput, SimpleConsistentHashFactory simpleConsistentHashFactory)
            throws IOException {
         //nothing to send
      }

      @Override
      public SimpleConsistentHashFactory readObject(ObjectInput objectInput)
            throws IOException, ClassNotFoundException {
         return new SimpleConsistentHashFactory();
      }
   }


}
