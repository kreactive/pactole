Pactole
===

Ceci est un projet qui permet d'enrichir d'enrichir la lib `akka-stream`.

Pour l'instant, il propose :

- de donner plusieurs implémentation à une même extension, avec `MultiImplementedExtension[T <: Extension]]`
- de découper un `Flow` en `Sink` + `Source` (attention, si ça ne provient pas de `Flow.fromSinkandSource`, ça peut faire des choses bizarres)
- un `StateZip` qui est essentiellement un `Flow` avec un inlet en plus permettant de modifier un état interne. 
Le flow principal renvoie le state courant avec l'élément entrant.
- un `StateFlow`, fondé sur `StateZip` qui considère que l'état est modifié par certaines valeurs du flow entrant
- un `StateReducer` qui modifie son état interne 
- un `eitherFanout` qui sépare les éléments d'un flow de `Either[A, B]` en des flows de `A` et `B`
- un `collectFanout` qui permet de récupérer une partie d'un flow sans perturber le flow initial.
- un `IdleGraphStageLogic` qui gère les idle dynamiques
- et de plus en plus de features

Le sous-projet `pactole-http` offre des utilitaires pour `akka-http`, notamment pour drainer les `HttpResponse` et créer des (`Un`)`Marshaller` à partir de la (dé)serialisation `play-json`.
 
Le sous-projet `pactole-testkit` permet de simplifier l'utilisation des probes dans les flows.
 Il donne aussi la possibilité d'utiliser un `MockScheduler`, pour éviter d'attendre les scheduled events d'akka.

## Installation

Pour la lib :

    libraryDependencies += "com.kreactive" %% "pactole" % "0.3.5"
    
Pour la lib http :

    libraryDependencies += "com.kreactive" %% "pactole-flow-persistence" % "0.3.5"
    
Pour le testkit :
    
    libraryDependencies += "com.kreactive" %% "pactole-testkit" % "0.3.5" % "test"



cross publish sur bintray :

    + publish          // cross publish sur les version scala
    
    bintrayRelease     // crée la release de la version


Crédits :

Cyrille Corpet      https://github.com/zozoens31
    
Julien Blondeau     https://github.com/captainju
    
Rémi Lavolée        https://github.com/rlavolee

    
