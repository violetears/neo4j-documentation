/*
 * Licensed to Neo4j under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Neo4j licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.neo4j.examples.socnet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Random;

import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;

import static com.google.common.collect.Iterables.addAll;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.internal.helpers.collection.Iterators.addToCollection;
import static org.neo4j.internal.helpers.collection.Iterators.single;

class SocnetTest
{
    private static final Random r = new Random( System.currentTimeMillis() );
    private static final int nrOfPersons = 20;

    private GraphDatabaseService graphDb;
    private PersonRepository personRepository;
    private DatabaseManagementService managementService;

    @TempDir
    private Path folder;

    @BeforeEach
    void setup() throws Exception
    {
        managementService = new DatabaseManagementServiceBuilder( folder ).build();
        graphDb = managementService.database( DEFAULT_DATABASE_NAME );
        try ( Transaction tx = graphDb.beginTx() )
        {
            personRepository = new PersonRepository( graphDb, tx );
            createPersons();
            setupFriendsBetweenPeople( 10 );
            tx.commit();
        }
    }

    @AfterEach
    void teardown()
    {
        managementService.shutdown();
    }

    @Test
    void addStatusAndRetrieveIt() throws Exception
    {
        Person person;
        try ( Transaction tx = graphDb.beginTx() )
        {
            person = getRandomPerson();
            person.addStatus( "Testing!" );
            tx.commit();
        }

        try ( Transaction tx = graphDb.beginTx() )
        {
            StatusUpdate update = person.getStatus().iterator().next();
            assertThat( update, notNullValue() );
            assertThat( update.getStatusText(), equalTo( "Testing!" ) );
            assertThat( update.getPerson(), equalTo( person ) );
        }
    }

    @Test
    void multipleStatusesComeOutInTheRightOrder() throws Exception
    {
        ArrayList<String> statuses = new ArrayList<>();
        statuses.add( "Test1" );
        statuses.add( "Test2" );
        statuses.add( "Test3" );

        try ( Transaction tx = graphDb.beginTx() )
        {
            Person person = getRandomPerson();
            for ( String status : statuses )
            {
                person.addStatus( status );
            }

            int i = statuses.size();
            for ( StatusUpdate update : person.getStatus() )
            {
                i--;
                assertThat( update.getStatusText(), equalTo( statuses.get( i ) ) );
            }
        }
    }

    @Test
    void removingOneFriendIsHandledCleanly()
    {
        Person person1;
        Person person2;
        long noOfFriends;
        try ( Transaction tx = graphDb.beginTx() )
        {
            person1 = personRepository.getPersonByName( "person#1" );
            person2 = personRepository.getPersonByName( "person#2" );
            person1.addFriend( person2 );

            noOfFriends = person1.getNrOfFriends();
            tx.commit();
        }

        try ( Transaction tx = graphDb.beginTx() )
        {
            person1.removeFriend( person2 );
            tx.commit();
        }

        try ( Transaction tx = graphDb.beginTx() )
        {
            long noOfFriendsAfterChange = person1.getNrOfFriends();
            assertThat( noOfFriends, equalTo( noOfFriendsAfterChange + 1 ) );
        }
    }

    @Test
    void retrieveStatusUpdatesInDateOrder() throws Exception
    {
        Person person;
        int numberOfStatuses;
        try ( Transaction tx = graphDb.beginTx() )
        {
            person = getRandomPersonWithFriends();
            numberOfStatuses = 20;

            for ( int i = 0; i < numberOfStatuses; i++ )
            {
                Person friend = getRandomFriendOf( person );
                friend.addStatus( "Dum-deli-dum..." );
            }
            tx.commit();
        }

        ArrayList<StatusUpdate> updates;
        try ( Transaction tx = graphDb.beginTx() )
        {
            updates = fromIterableToArrayList( person.friendStatuses() );

            assertThat( updates.size(), equalTo( numberOfStatuses ) );
            assertUpdatesAreSortedByDate( updates );
        }
    }

    @Test
    void friendsOfFriendsWorks() throws Exception
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            Person person = getRandomPerson();
            Person friend = getRandomFriendOf( person );

            for ( Person friendOfFriend : friend.getFriends() )
            {
                if ( !friendOfFriend.equals( person ) )
                { // You can't be friends with yourself.
                    assertThat( person.getFriendsOfFriends(), hasItems( friendOfFriend ) );
                }
            }
        }
    }

    @Test
    void shouldReturnTheCorrectPersonFromAnyStatusUpdate() throws Exception
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            Person person = getRandomPerson();
            person.addStatus( "Foo" );
            person.addStatus( "Bar" );
            person.addStatus( "Baz" );

            for ( StatusUpdate status : person.getStatus() )
            {
                assertThat( status.getPerson(), equalTo( person ) );
            }
        }
    }

    @Test
    void getPathBetweenFriends() throws Exception
    {
        deleteSocialGraph();

        Person start;
        Person middleMan1;
        Person middleMan2;
        Person endMan;
        try ( Transaction tx = graphDb.beginTx() )
        {
            start = personRepository.createPerson( "start" );
            middleMan1 = personRepository.createPerson( "middle1" );
            middleMan2 = personRepository.createPerson( "middle2" );
            endMan = personRepository.createPerson( "endMan" );

            // Start -> middleMan1 -> middleMan2 -> endMan

            start.addFriend( middleMan1 );
            middleMan1.addFriend( middleMan2 );
            middleMan2.addFriend( endMan );
            tx.commit();
        }

        try ( Transaction tx = graphDb.beginTx() )
        {
            Iterable<Person> path = start.getShortestPathTo( endMan, 4 );

            assertPathIs( path, start, middleMan1, middleMan2, endMan );
            //assertThat( path, matchesPathByProperty(Person.NAME, "start", "middle1", "middle2", "endMan"));
        }
    }

    @Test
    void singleFriendRecommendation() throws Exception
    {
        deleteSocialGraph();
        Person a;
        Person e;
        try ( Transaction tx = graphDb.beginTx() )
        {
            a = personRepository.createPerson( "a" );
            Person b = personRepository.createPerson( "b" );
            Person c = personRepository.createPerson( "c" );
            Person d = personRepository.createPerson( "d" );
            e = personRepository.createPerson( "e" );

            // A is friends with B,C and D
            a.addFriend( b );
            a.addFriend( c );
            a.addFriend( d );

            // E is also friend with B, C and D
            e.addFriend( b );
            e.addFriend( c );
            e.addFriend( d );
            tx.commit();
        }

        try ( Transaction tx = graphDb.beginTx() )
        {
            Person recommendation = single( a.getFriendRecommendation( 1 ).iterator() );
            assertThat( recommendation, equalTo( e ) );
        }
    }

    @Test
    void weightedFriendRecommendation() throws Exception
    {
        deleteSocialGraph();
        Person a;
        Person e;
        Person f;
        try ( Transaction tx = graphDb.beginTx() )
        {
            a = personRepository.createPerson( "a" );
            Person b = personRepository.createPerson( "b" );
            Person c = personRepository.createPerson( "c" );
            Person d = personRepository.createPerson( "d" );
            e = personRepository.createPerson( "e" );
            f = personRepository.createPerson( "f" );

            // A is friends with B,C and D
            a.addFriend( b );
            a.addFriend( c );
            a.addFriend( d );

            // E is only friend with B
            e.addFriend( b );

            // F is friend with B, C, D
            f.addFriend( b );
            f.addFriend( c );
            f.addFriend( d );
            tx.commit();
        }

        try ( Transaction tx = graphDb.beginTx() )
        {
            ArrayList<Person> recommendations = fromIterableToArrayList( a.getFriendRecommendation( 2 ).iterator() );
            assertThat( recommendations.get( 0 ), equalTo( f ));
            assertThat( recommendations.get( 1 ), equalTo( e ));
        }
    }

    private <T> ArrayList<T> fromIterableToArrayList( Iterator<T> iterable )
    {
        ArrayList<T> collection = new ArrayList<>();
        addToCollection( iterable, collection );
        return collection;
    }

    private void assertPathIs( Iterable<Person> path,
                                       Person... expectedPath )
    {
        ArrayList<Person> pathArray = new ArrayList<>();
        addAll( pathArray, path );
        assertThat( pathArray.size(), equalTo( expectedPath.length ) );
        for ( int i = 0; i < expectedPath.length; i++ )
        {
            assertThat( pathArray.get( i ), equalTo( expectedPath[ i ] ) );
        }
    }

    private void setupFriendsBetweenPeople( int maxNrOfFriendsEach )
    {
        for ( Person person : personRepository.getAllPersons() )
        {
            int nrOfFriends = r.nextInt( maxNrOfFriendsEach ) + 1;
            for ( int j = 0; j < nrOfFriends; j++ )
            {
                person.addFriend( getRandomPerson() );
            }
        }
    }

    private Person getRandomPerson()
    {
        return personRepository.getPersonByName( "person#"
                + r.nextInt( nrOfPersons ) );
    }

    private void deleteSocialGraph()
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            for ( Person person : personRepository.getAllPersons() )
            {
                personRepository.deletePerson( person );
            }
        }
    }

    private Person getRandomFriendOf( Person p )
    {
        ArrayList<Person> friends = new ArrayList<>();
        addToCollection( p.getFriends().iterator(), friends );
        return friends.get( r.nextInt( friends.size() ) );
    }

    private Person getRandomPersonWithFriends()
    {
        Person p;
        do
        {
            p = getRandomPerson();
        }
        while ( p.getNrOfFriends() == 0 );
        return p;
    }

    private void createPersons() throws Exception
    {
        for ( int i = 0; i < nrOfPersons; i++ )
        {
            personRepository.createPerson( "person#" + i );
        }
    }

    private void assertUpdatesAreSortedByDate(
            ArrayList<StatusUpdate> statusUpdates )
    {
        Date date = new Date( 0 );
        for ( StatusUpdate update : statusUpdates )
        {
            org.junit.Assert.assertTrue( date.getTime() < update.getDate().getTime() );
            // TODO: Should be assertThat(date, lessThan(update.getDate));
        }
    }
}
