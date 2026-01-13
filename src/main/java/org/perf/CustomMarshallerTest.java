package org.perf;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.cache.impl.InfinispanCacheFactory;
import org.jgroups.Receiver;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.util.Util;


/**
 * Uses custom-marshaller.xml to install a number of different marshallers
 * @author Bela Ban
 */
public class CustomMarshallerTest implements Receiver {
    protected CacheFactory<String,Person> person_factory;
    protected Cache<String,Person>        persons;

    static {
        ClassConfigurator.add(Person.ID, Person.class);
    }

    protected void start(String name) throws Exception {
        person_factory=new InfinispanCacheFactory<>();
        person_factory.init("persons.xml", false, 0, name);
        persons=person_factory.create("persons", name);
        System.out.printf("%d entries in persons: %s\n", persons.size(), persons.getContents());

        int num=1;
        for(;;) {
            Util.keyPress("<enter> to add:");
            int n=num++;
            try {
                persons.put(name + n, new Person(n, "hello-" + n));
                System.out.printf("%d entries in persons: %s\n", persons.size(), persons.getContents());
            }
            catch(Throwable t) {
                System.out.printf("-- failure in put(): %s\n", t.getMessage());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        CustomMarshallerTest c=new CustomMarshallerTest();
        c.start(args[0]);
    }


}
