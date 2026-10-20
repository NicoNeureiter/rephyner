Reduced phylogenetic network rephyner  
======

rephyner is a BEAST 2 package for Bayesian inference of *reduced* ancestral
reassortment graphs (rARGs): the part of an ARG that appears in at least one
local tree. See [documentation/](documentation/README.md) for the idea, the
model, the operators and the current state of the project.

Building rephyner
---------------

In order to build rephyner from the source, you will need the following:

1. [OpenJDK](https://adoptopenjdk.net) v8 or later,
2. The Apache Ant build tool.

Once these are installed, open a shell in the root directory of this repository
and use

    $ ant

to build the package.

License
-------

rephyner is free (as in freedom) software and is distributed under the terms of
version 3 of the GNU General Public License.  A copy of this license is found
in the file named `COPYING`.
