# functional-streams

This library provides the model and small set of tools 
to deal with async IO in functional style, without inversion of control.

**functional-streams** was created to eliminate complexities accompanying
with implementation the client/server async request-response protocols.

**functional-streams** is not dedicated to some concrete protocol or 
implementation. It's just a model, which provides abilities to create
layered architecture for request/response processing code. As a result
the code becomes easy to read, easy to extend, and easy to test.

If you're familiar with **Netty**'s architecture, then you could find some 
similarities here, in **functional-streams**. However, this is not 
the same thing. 

**Netty** gives you ability to build the chain of filters 
to transform low-level bytes of data to the objects you're wish to deal with.
Then you register the callback-handler, to process all the business logic
asynchronously, by using netty- future listeners. This is great library, but 
callback-style code quickly becomes hard to understand, and hard to maintain.
Your business logic becomes spread across the callback-handlers.
This is the problem known as "inversion of control".
