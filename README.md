# functional-streams

**functional-streams** (**FS**) was created to eliminate complexities
arising when dealing with asynchronous IO.

**FS** relies heavily on Scala's *Future*. It implies you have to fully
understand how *Future* works, before continue reading.





-------------------------------------------------------------------------
**functional-streams** (**FS**) was created to eliminate complexities accompanying
with implementation the client/server async request-response protocols.

**FS** is not dedicated to some concrete protocol or 
implementation. It's just a model, which provides abilities to create
layered architecture for request/response processing code. It allows
to write the code in functional style, resulting with more clean code 
that easy to read, easy to extend, and easy to test.

The idea is to wrap the code dealing with IO around Scala's **Future**.
There is trait FutureUtils which extends Future's interface with 
number of useful methods. Most of them are just synonyms for existing methods,
and introduced in order to emphasize your intentions. 
For example, there is method-operator **>>=** which is synonym for **flatMap**.
So, instead of

```
fnA flatMap fnB flatMap fnC
```

with >>= operator code will look like:

```
fnA >>= fnB >>= fnC
```

which is visually more notable what is going on here. Especially, when
such code is going to grow.

Another example with operator **>>** which works like a regular **flatMap**, 
but ignores it's input parameter:

```
fnA >> fnB >> fnC
```

Effectively, it's way to chain your futures in sequence. Using **flatMap** 
this code will look like:

```
fnA flatMap (_ => fnB) flatMap (_ => fnC)
```

Of course you may use **for**-comprehensions for such kind of things, but
your code will grow down the page. Mostly you will combine both ways.

There is also:
* operator **<|>** which works like logical *OR* for two futures
* method **await** which is analog of *Await.result*
* methods **success** and **fail**
* and other useful things




=====

Most asynchronous systems provides *Publish*/*Subscribe* interface.
Suppose, we have a *Publisher*:

```
trait Publisher[A]{
    def subscribe(subscriber: Subscriber[A])
    def unsubscribe(subscriber: Subscriber[A])
}
```

which may notify subscribers about appearance of new element of type `A`.
And this is how *Subscriber* interface may look like:

```
trait Subscriber[A]{
    def push(elem: Try[A])
}
```

When *Publisher* emits some event, it notifies its subscribers by calling
subscriber's *push* method.

This is easy to implement *Publisher* and *Subscriber*, but it not so useful
to work with them. Especially if we want to finally consume some `B` type 
(so we have to transform `A` type to `B` ), or filter out some elements, 
or build complex logic on consumer.

